package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.SecretEncryptor;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Uploading, validating and activating an EHR snapshot.
 *
 * Uploading validates the file and automatically activates its usable rows in
 * the same transaction, superseding the previous snapshot. Rejected rows are
 * listed in the validation report. A file with no usable rows is rejected and
 * leaves the active snapshot unchanged.
 *
 * <h2>Drift is flagged, never applied</h2>
 *
 * When a new snapshot carries a different name or phone for an account that is
 * already active, the account is not updated. Silently rebinding an active
 * account to changed contact details is an account-takeover path: a wrong or
 * malicious row in an import would redirect a patient's verification codes.
 * HIM reviews it instead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EhrImportService {

    /**
     * Day-first date formats, in the order they are tried. Month-first is only
     * tried when day-first is impossible (a "day" above 12 in the month slot).
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = java.util.stream.Stream.of(
                    "yyyy-M-d", "yyyy/M/d", "yyyyMMdd",
                    "d/M/yyyy", "d-M-yyyy", "d.M.yyyy", "d M yyyy",
                    "d/M/yy", "d-M-yy", "d.M.yy", "d M yy",
                    "d MMM yyyy", "d-MMM-yyyy", "d/MMM/yyyy", "d MMMM yyyy", "d-MMMM-yyyy",
                    "MMM d, yyyy", "MMMM d, yyyy", "MMM d yyyy", "MMMM d yyyy")
            .map(pattern -> new java.time.format.DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(pattern.replace("yy", "uu").replace("uuuu", "uuuu"))
                    .toFormatter(java.util.Locale.ENGLISH))
            .toList();

    private static final List<DateTimeFormatter> MONTH_FIRST_FORMATS = java.util.stream.Stream.of(
                    "M/d/yyyy", "M-d-yyyy")
            .map(pattern -> DateTimeFormatter.ofPattern(pattern.replace("yyyy", "uuuu"), java.util.Locale.ENGLISH))
            .toList();

    /**
     * Column names accepted for each field, compared after lower-casing and
     * turning every run of non-letters into an underscore ("EHR No." becomes
     * ehr_no, "D.O.B" becomes d_o_b).
     */
    private static final Map<String, List<String>> COLUMN_ALIASES = Map.ofEntries(
            Map.entry("ehr_number", List.of("ehr_number", "ehr_no", "ehr", "ehrnumber", "ehr_id",
                    "hospital_number", "hospital_no", "patient_id", "patient_number", "patient_no",
                    "mrn", "record_number", "folder_number", "folder_no", "file_number", "file_no",
                    "card_number", "card_no")),
            Map.entry("full_name", List.of("full_name", "fullname", "name", "patient_name", "patient_full_name",
                    "names")),
            Map.entry("first_name", List.of("first_name", "firstname", "given_name", "forename", "other_names",
                    "othernames")),
            Map.entry("middle_name", List.of("middle_name", "middlename", "middle")),
            Map.entry("last_name", List.of("last_name", "lastname", "surname", "family_name")),
            Map.entry("date_of_birth", List.of("date_of_birth", "dob", "d_o_b", "birth_date", "birthdate",
                    "date_birth", "birthday")),
            Map.entry("phone_number", List.of("phone_number", "phone", "phone_no", "mobile", "mobile_number",
                    "mobile_no", "telephone", "tel", "gsm", "gsm_number", "contact_number", "contact_phone")),
            Map.entry("email", List.of("email", "email_address", "e_mail", "mail", "contact_email")),
            Map.entry("clinic", List.of("clinic", "department", "unit", "ward")),
            Map.entry("patient_status", List.of("patient_status", "status")));

    /** Only the first issues are kept in the report, so a bad file cannot fill the column. */
    private static final int MAX_REPORTED_ISSUES = 200;

    private final EhrImportRepository importRepository;
    private final EhrRecordRepository recordRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final SecretEncryptor secretEncryptor;

    @Transactional
    public EhrVerificationImport upload(MultipartFile file, LocalDate sourceAsAt) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("The file is empty");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read the uploaded file");
        }

        String checksum = Tokens.hash(new String(bytes, StandardCharsets.UTF_8));
        // One import per file (the checksum is unique). A file whose earlier import
        // was rejected may be checked again: the rejection may have come from a
        // server fault rather than the data, and the unique key would otherwise
        // block that file for ever. Its import record is reused.
        EhrVerificationImport previous = importRepository.findByFileChecksum(checksum).orElse(null);
        if (previous != null && previous.getStatus() != ImportStatus.REJECTED) {
            throw new IllegalArgumentException(
                    "This exact file has already been uploaded. Export a fresh snapshot.");
        }

        if (sourceAsAt.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The extract date cannot be in the future");
        }

        EhrVerificationImport ehrImport = previous != null ? previous : new EhrVerificationImport();
        if (previous != null) {
            ehrImport.setValidationReport(null);
            ehrImport.setRowCount(0);
            ehrImport.setValidRowCount(0);
            ehrImport.setRejectedRowCount(0);
            log.info("Re-checking previously rejected EHR import {}", previous.getPublicId());
        }
        ehrImport.setFileName(file.getOriginalFilename());
        ehrImport.setFileChecksum(checksum);
        ehrImport.setFileSizeBytes((long) bytes.length);
        ehrImport.setSourceAsAt(sourceAsAt);
        ehrImport.setStatus(ImportStatus.VALIDATING);
        ehrImport.setUploadedAt(LocalDateTime.now());
        CurrentUser.get().ifPresent(u ->
                ehrImport.setUploadedBy(userRepository.findById(u.getUserId()).orElse(null)));
        importRepository.save(ehrImport);

        ParseResult result = parse(bytes);

        ehrImport.setRowCount(result.totalRows);
        ehrImport.setValidRowCount(result.records.size());
        ehrImport.setRejectedRowCount(result.totalRows - result.records.size());

        // Rows that fail are skipped and listed; the rest load. The file is only
        // refused when it cannot be read, lacks a required column, or has no
        // usable row at all. One bad line used to reject the whole export.
        if (result.fatal() || result.records.isEmpty()) {
            ehrImport.setStatus(ImportStatus.REJECTED);
            ehrImport.setValidationReport(report(result, "Nothing was loaded."));
            importRepository.save(ehrImport);
            log.warn("EHR import {} rejected: no usable rows ({} rows read, {} issues)",
                    ehrImport.getPublicId(), result.totalRows, result.errors.size());
            return ehrImport;
        }

        result.records.forEach(record -> {
            record.setEhrImport(ehrImport);
            recordRepository.save(record);
        });

        ehrImport.setStatus(ImportStatus.VALIDATED);
        ehrImport.setValidationReport(report(result, result.errors.isEmpty()
                ? "All %d rows loaded.".formatted(result.records.size())
                : "%d of %d rows loaded; %d skipped.".formatted(
                result.records.size(), result.totalRows, result.totalRows - result.records.size())));
        importRepository.save(ehrImport);

        log.info("EHR import {} validated: {} of {} rows loaded, extract dated {}",
                ehrImport.getPublicId(), result.records.size(), result.totalRows, sourceAsAt);
        return activateValidated(ehrImport, "Automatically activated after successful upload");
    }

    /**
     * Makes a validated snapshot the one enrolment matches against.
     *
     * Supersedes the previous one rather than deleting it, so the old snapshot
     * remains available for audit and for answering "what did the record say
     * when this account was activated".
     */
    @Transactional
    public EhrVerificationImport activate(String importPublicId, String reason) {
        EhrVerificationImport ehrImport = importRepository.findByPublicId(importPublicId)
                .orElseThrow(() -> new IllegalArgumentException("No such import"));

        return activateValidated(ehrImport, reason);
    }

    /** Shared by automatic upload activation and the legacy activation endpoint. */
    private EhrVerificationImport activateValidated(EhrVerificationImport ehrImport, String reason) {
        if (ehrImport.getStatus() != ImportStatus.VALIDATED) {
            throw new IllegalArgumentException(
                    "Only a validated import can be activated. This one is "
                            + ehrImport.getStatus() + ".");
        }

        List<EhrVerificationRecord> drift =
                recordRepository.findDriftAgainstActivePatients(ehrImport.getId());

        drift.forEach(record -> patientRepository.findByEhrNumber(record.getEhrNumber())
                .ifPresent(patient -> {
                    patient.setDriftFlagged(true);
                    patient.setDriftFlaggedAt(LocalDateTime.now());
                    patient.setDriftDetails(
                            "Snapshot dated %s carries different details for this account. "
                                    + "Review before relying on the stored contact route."
                                    .formatted(ehrImport.getSourceAsAt()));
                    patientRepository.save(patient);
                }));

        LocalDateTime now = LocalDateTime.now();
        String actor = CurrentUser.usernameOrSystem();

        importRepository.findFirstByStatus(ImportStatus.ACTIVE).ifPresent(previous -> {
            previous.setStatus(ImportStatus.SUPERSEDED);
            previous.setSupersededAt(now);
            previous.setSupersededByImportId(ehrImport.getId());
            importRepository.save(previous);
        });

        ehrImport.setStatus(ImportStatus.ACTIVE);
        ehrImport.setActivatedAt(now);
        ehrImport.setActivatedBy(actor);
        ehrImport.setDriftDetectedCount(drift.size());
        importRepository.save(ehrImport);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.EHR_IMPORT_ACTIVATED)
                .entityType("EhrVerificationImport")
                .entityId(ehrImport.getId())
                .details("%d records, extract dated %s, %d accounts flagged for drift"
                        .formatted(ehrImport.getValidRowCount(), ehrImport.getSourceAsAt(), drift.size()))
                .reason(reason)
                .build());

        log.info("EHR import {} activated by {}: {} records, {} drift flags",
                ehrImport.getPublicId(), actor, ehrImport.getValidRowCount(), drift.size());

        return ehrImport;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<EhrVerificationImport> activeImport() {
        return importRepository.findFirstByStatus(ImportStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<EhrVerificationImport> recentImports() {
        return importRepository.findTop20ByOrderByUploadedAtDesc();
    }

    // -----------------------------------------------------------------

    private ParseResult parse(byte[] bytes) {
        List<EhrVerificationRecord> records = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        java.util.Set<String> seenNumbers = new java.util.HashSet<>();
        int total = 0;

        String text = new String(bytes, StandardCharsets.UTF_8);
        // Excel's "CSV UTF-8" starts with a byte-order mark, which made the first
        // column name unrecognisable.
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(detectDelimiter(text))
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true).setTrim(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .build();

        try (CSVParser parser = CSVParser.parse(new java.io.StringReader(text), format)) {

            Map<String, String> columns = resolveColumns(parser.getHeaderNames());
            List<String> missing = new ArrayList<>();
            if (!columns.containsKey("ehr_number")) {
                missing.add("an EHR number (" + String.join(", ", COLUMN_ALIASES.get("ehr_number").subList(0, 4)) + ", ...)");
            }
            if (!columns.containsKey("full_name") && !columns.containsKey("first_name") && !columns.containsKey("last_name")) {
                missing.add("a name (full_name, or first_name and last_name / surname)");
            }
            if (!columns.containsKey("date_of_birth")) {
                missing.add("a date of birth (date_of_birth, dob, birth_date)");
            }
            if (!missing.isEmpty()) {
                errors.add("Missing column for " + String.join("; ", missing)
                        + ". Columns found: " + String.join(", ", parser.getHeaderNames()) + ".");
                return new ParseResult(0, records, errors, true);
            }

            for (CSVRecord row : parser) {
                if (isBlankRow(row)) {
                    continue;
                }
                total++;
                long line = row.getRecordNumber() + 1;

                String ehrNumber = field(row, columns, "ehr_number");
                String fullName = nameOf(row, columns);
                String dob = field(row, columns, "date_of_birth");

                if (ehrNumber.isBlank()) {
                    errors.add("Line " + line + ": skipped, no EHR number");
                    continue;
                }
                if (!seenNumbers.add(ehrNumber)) {
                    // The first row for a number is kept; matching needs exactly one.
                    errors.add("Line " + line + ": skipped, EHR number " + ehrNumber
                            + " already appeared earlier in the file");
                    continue;
                }
                if (fullName.isBlank()) {
                    errors.add("Line " + line + ": skipped, no name for " + ehrNumber);
                    continue;
                }
                LocalDate dateOfBirth = parseDate(dob);
                if (dateOfBirth == null) {
                    errors.add("Line " + line + ": skipped, date of birth '" + dob + "' for " + ehrNumber
                            + " is not a date the importer understands");
                    continue;
                }
                if (dateOfBirth.isAfter(LocalDate.now()) || dateOfBirth.getYear() < 1900) {
                    errors.add("Line " + line + ": skipped, date of birth " + dateOfBirth + " for " + ehrNumber
                            + " is not plausible");
                    continue;
                }

                String normalisedPhone = normalisePhone(field(row, columns, "phone_number"));
                String normalisedEmail = normaliseEmail(field(row, columns, "email"));
                if (normalisedPhone == null && normalisedEmail == null) {
                    // Loaded anyway: the patient can still be enrolled at a desk or
                    // through a manual check. Reported so the gap is visible.
                    errors.add("Line " + line + ": loaded without a phone or email for " + ehrNumber
                            + "; online enrolment will not be able to send a code");
                }

                EhrVerificationRecord record = new EhrVerificationRecord();
                record.setEhrNumber(ehrNumber);
                record.setFullName(fullName);
                record.setDateOfBirthHash(Tokens.hash(dateOfBirth.toString()));
                record.setDateOfBirthMasked(maskDate(dateOfBirth));
                record.setDateOfBirthEncrypted(secretEncryptor.encrypt(dateOfBirth.toString()));

                if (normalisedPhone != null) {
                    record.setPhoneHash(Tokens.hash(normalisedPhone));
                    record.setPhoneMasked(maskPhone(normalisedPhone));
                    record.setPhoneEncrypted(secretEncryptor.encrypt(normalisedPhone));
                }
                if (normalisedEmail != null) {
                    record.setEmailHash(Tokens.hash(normalisedEmail));
                    record.setEmailMasked(maskEmail(normalisedEmail));
                    record.setEmailEncrypted(secretEncryptor.encrypt(normalisedEmail));
                }

                String clinic = field(row, columns, "clinic");
                String status = field(row, columns, "patient_status");
                record.setClinic(clinic.isBlank() ? null : truncate(clinic, 100));
                record.setPatientStatus(status.isBlank() ? null : truncate(status, 30));
                record.setIsActiveRecord(true);
                records.add(record);
            }

            if (total == 0) {
                errors.add("The file has column names but no rows");
            }
        } catch (Exception e) {
            errors.add("Could not read the file as CSV: " + e.getMessage());
            return new ParseResult(total, records, errors, true);
        }

        return new ParseResult(total, records, errors, false);
    }

    /** Comma, semicolon or tab, whichever the header line uses most. */
    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        long commas = header.chars().filter(c -> c == ',').count();
        long semicolons = header.chars().filter(c -> c == ';').count();
        long tabs = header.chars().filter(c -> c == '\t').count();
        if (semicolons > commas && semicolons >= tabs) {
            return ';';
        }
        if (tabs > commas && tabs > semicolons) {
            return '\t';
        }
        return ',';
    }

    private static String canonical(String header) {
        return header == null ? "" : header.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** Field name to the header actually used in this file. */
    private static Map<String, String> resolveColumns(List<String> headers) {
        Map<String, String> byCanonical = new java.util.HashMap<>();
        for (String header : headers) {
            byCanonical.putIfAbsent(canonical(header), header);
        }
        Map<String, String> resolved = new java.util.HashMap<>();
        COLUMN_ALIASES.forEach((field, aliases) -> {
            for (String alias : aliases) {
                String header = byCanonical.get(alias);
                if (header != null) {
                    resolved.put(field, header);
                    break;
                }
            }
        });
        // "status" alone is too vague to be a patient status if a clinic-style
        // column already took it; nothing else needs de-duplicating.
        return resolved;
    }

    private static boolean isBlankRow(CSVRecord row) {
        for (String value : row) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static String field(CSVRecord row, Map<String, String> columns, String field) {
        String header = columns.get(field);
        if (header == null || !row.isMapped(header) || !row.isSet(header)) {
            return "";
        }
        String v = row.get(header);
        return v == null ? "" : v.trim();
    }

    /** full_name, or first, middle and last name joined. */
    private static String nameOf(CSVRecord row, Map<String, String> columns) {
        String full = field(row, columns, "full_name");
        if (!full.isBlank()) {
            return truncate(full.replaceAll("\\s+", " "), 200);
        }
        String joined = String.join(" ", java.util.stream.Stream.of(
                        field(row, columns, "first_name"),
                        field(row, columns, "middle_name"),
                        field(row, columns, "last_name"))
                .filter(part -> !part.isBlank())
                .toList());
        return truncate(joined.replaceAll("\\s+", " "), 200);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String report(ParseResult result, String headline) {
        StringBuilder out = new StringBuilder(headline);
        int shown = Math.min(result.errors.size(), MAX_REPORTED_ISSUES);
        for (int i = 0; i < shown; i++) {
            out.append('\n').append(result.errors.get(i));
        }
        if (result.errors.size() > shown) {
            out.append('\n').append("... and ").append(result.errors.size() - shown).append(" more.");
        }
        return out.toString();
    }

    private String normaliseEmail(String raw) {
        if (raw == null || raw.isBlank() || !raw.contains("@") || raw.endsWith("@")) {
            return null;
        }
        return raw.trim().toLowerCase();
    }


    private String maskEmail(String normalised) {
        int at = normalised.indexOf('@');
        String local = normalised.substring(0, at);
        String shown = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return shown + "***" + normalised.substring(at);
    }

    /**
     * Reads the date forms hospital systems and spreadsheets produce: ISO,
     * day-first with / - . or spaces, month names, two-digit years, a trailing
     * time, and Excel's day-number dates.
     */
    static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        // Drop a trailing time: "1985-03-12 00:00:00", "1985-03-12T00:00".
        value = value.replaceFirst("[T ]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?\\s*([AaPp][Mm])?$", "").trim();

        // Excel stores dates as days since 30 December 1899.
        if (value.matches("\\d{4,5}(\\.0+)?")) {
            int days = (int) Double.parseDouble(value);
            if (days > 0 && days < 80000) {
                return LocalDate.of(1899, 12, 30).plusDays(days);
            }
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                LocalDate date = LocalDate.parse(value, format);
                // Two-digit years: anything later than this year is last century.
                if (date.isAfter(LocalDate.now()) && format.toString().contains("ReducedValue")) {
                    date = date.minusYears(100);
                }
                return date;
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        for (DateTimeFormatter format : MONTH_FIRST_FORMATS) {
            try {
                return LocalDate.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // not month-first either
            }
        }
        return null;
    }

    /**
     * Normalises to the last nine digits.
     *
     * The same Nigerian number is written 08012345678, +2348012345678 and
     * 2348012345678 in different systems. Comparing them literally would make
     * corroboration fail for a patient whose number is correct, which is worse
     * than useless: they would end up in the exception queue for a formatting
     * difference.
     */
    static String normalisePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() < 9) {
            return null;
        }
        return digits.substring(digits.length() - 9);
    }

    private String maskDate(LocalDate date) {
        return "**/**/" + date.getYear();
    }

    private String maskPhone(String normalised) {
        return "*******" + normalised.substring(normalised.length() - 4);
    }

    private record ParseResult(int totalRows, List<EhrVerificationRecord> records, List<String> errors, boolean fatal) {
    }
}
