package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
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

/**
 * Uploading, validating and activating an EHR snapshot.
 *
 * <h2>Validate everything before committing anything</h2>
 *
 * A malformed row rejects the whole file. A partial import leaves half a
 * patient list loaded with no way to tell which half, and the failure surfaces
 * later as a patient who cannot enrol for no visible reason.
 *
 * <h2>Upload and activate are two steps</h2>
 *
 * Uploading parses and validates. Activating makes the snapshot the one
 * enrolment matches against, and supersedes the previous one. Splitting them
 * means a file can be checked, its report read, and the switch made
 * deliberately rather than as a side effect of a drag and drop.
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

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy")
    };

    private static final String[] REQUIRED_HEADERS = {
            "ehr_number", "full_name", "date_of_birth", "phone_number"
    };

    private final EhrImportRepository importRepository;
    private final EhrRecordRepository recordRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

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
        if (importRepository.existsByFileChecksum(checksum)) {
            throw new IllegalArgumentException(
                    "This exact file has already been uploaded. Export a fresh snapshot, "
                            + "or activate the existing import instead.");
        }

        if (sourceAsAt.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The extract date cannot be in the future");
        }

        EhrVerificationImport ehrImport = new EhrVerificationImport();
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
        ehrImport.setRejectedRowCount(result.errors.size());

        if (!result.errors.isEmpty()) {
            // Nothing is loaded. The report names every failing line so the
            // uploader can fix the export rather than guess.
            ehrImport.setStatus(ImportStatus.REJECTED);
            ehrImport.setValidationReport(String.join("\n", result.errors));
            importRepository.save(ehrImport);
            log.warn("EHR import {} rejected: {} of {} rows failed validation",
                    ehrImport.getPublicId(), result.errors.size(), result.totalRows);
            return ehrImport;
        }

        result.records.forEach(record -> {
            record.setEhrImport(ehrImport);
            recordRepository.save(record);
        });

        ehrImport.setStatus(ImportStatus.VALIDATED);
        ehrImport.setValidationReport("All %d rows valid.".formatted(result.records.size()));
        importRepository.save(ehrImport);

        log.info("EHR import {} validated: {} rows, extract dated {}",
                ehrImport.getPublicId(), result.records.size(), sourceAsAt);
        return ehrImport;
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

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true).setTrim(true)
                .build();

        try (CSVParser parser = CSVParser.parse(
                new BufferedReader(new InputStreamReader(
                        new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)),
                format)) {

            for (String header : REQUIRED_HEADERS) {
                if (!parser.getHeaderMap().containsKey(header)) {
                    errors.add("Missing required column: " + header
                            + ". Expected columns: " + String.join(", ", REQUIRED_HEADERS)
                            + " (clinic and patient_status are optional).");
                }
            }
            if (!errors.isEmpty()) {
                return new ParseResult(0, records, errors);
            }

            for (CSVRecord row : parser) {
                total++;
                long line = row.getRecordNumber() + 1;

                String ehrNumber = value(row, "ehr_number");
                String fullName = value(row, "full_name");
                String dob = value(row, "date_of_birth");
                String phone = value(row, "phone_number");

                if (ehrNumber.isBlank()) {
                    errors.add("Line " + line + ": ehr_number is empty");
                    continue;
                }
                if (!seenNumbers.add(ehrNumber)) {
                    // Two rows for one patient means the export is wrong, and
                    // matching would be non-deterministic.
                    errors.add("Line " + line + ": ehr_number " + ehrNumber
                            + " appears more than once in this file");
                    continue;
                }
                if (fullName.isBlank()) {
                    errors.add("Line " + line + ": full_name is empty for " + ehrNumber);
                    continue;
                }

                LocalDate dateOfBirth = parseDate(dob);
                if (dateOfBirth == null) {
                    errors.add("Line " + line + ": date_of_birth '" + dob
                            + "' is not a date. Use YYYY-MM-DD, DD/MM/YYYY or DD-MM-YYYY.");
                    continue;
                }
                if (dateOfBirth.isAfter(LocalDate.now())) {
                    errors.add("Line " + line + ": date_of_birth is in the future");
                    continue;
                }

                String normalisedPhone = normalisePhone(phone);

                EhrVerificationRecord record = new EhrVerificationRecord();
                record.setEhrNumber(ehrNumber);
                record.setFullName(fullName);
                record.setDateOfBirthHash(Tokens.hash(dateOfBirth.toString()));
                record.setDateOfBirthMasked(maskDate(dateOfBirth));
                if (normalisedPhone != null) {
                    record.setPhoneHash(Tokens.hash(normalisedPhone));
                    record.setPhoneMasked(maskPhone(normalisedPhone));
                }
                record.setClinic(optional(row, "clinic"));
                record.setPatientStatus(optional(row, "patient_status"));
                record.setIsActiveRecord(true);
                records.add(record);
            }

            if (total == 0) {
                errors.add("The file has a header but no rows");
            }
        } catch (Exception e) {
            errors.add("Could not read the file as CSV: " + e.getMessage());
        }

        return new ParseResult(total, records, errors);
    }

    private String value(CSVRecord row, String column) {
        try {
            String v = row.get(column);
            return v == null ? "" : v.trim();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private String optional(CSVRecord row, String column) {
        String v = row.isMapped(column) ? value(row, column) : "";
        return v.isBlank() ? null : v;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, format);
            } catch (DateTimeParseException ignored) {
                // try the next format
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

    private record ParseResult(int totalRows, List<EhrVerificationRecord> records, List<String> errors) {
    }
}
