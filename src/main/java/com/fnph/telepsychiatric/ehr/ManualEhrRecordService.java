package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.ehr.api.ManualEhrRecordRequest;
import com.fnph.telepsychiatric.ehr.api.ManualEhrRecordResponse;
import com.fnph.telepsychiatric.ehr.api.ManualEhrSyncRequest;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.SecretEncryptor;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ManualEhrRecordService {

    private final ManualEhrRecordRepository manualRepository;
    private final EhrImportRepository importRepository;
    private final EhrRecordRepository recordRepository;
    private final PatientRepository patientRepository;
    private final SecretEncryptor encryptor;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<ManualEhrRecordResponse> list() {
        EhrVerificationImport active =
                importRepository.findFirstByStatus(ImportStatus.ACTIVE)
                        .orElse(null);

        return manualRepository.findAllByOrderByUpdatedAtDesc()
                .map(row -> response(
                        row,
                        imported(active, row.getEhrNumber()),
                        active != null))
                .toList();
    }

    @Transactional
    public ManualEhrRecordResponse create(
            ManualEhrRecordRequest request) {

        String number = normalizeEhrNumber(
                request.ehrNumber());

        if (number == null) {
            throw new IllegalArgumentException(
                    "EHR number is required.");
        }

        if (manualRepository.findByEhrNumber(number).isPresent()) {
            throw new IllegalArgumentException(
                    "A manual EHR record already exists for "
                            + number
                            + ". Edit that record instead.");
        }

        ManualEhrRecord row = new ManualEhrRecord();

        apply(row, request, false);

        ManualEhrRecord saved =
                manualRepository.save(row);

        audit(
                saved,
                AuditAction.EHR_MANUAL_RECORD_CREATED,
                request.reason(),
                "Manual EHR record created");

        return currentResponse(saved);
    }

    @Transactional
    public ManualEhrRecordResponse update(
            String publicId,
            ManualEhrRecordRequest request) {

        ManualEhrRecord row = require(publicId);

        if (request.version() == null
                || !Objects.equals(
                        row.getVersion(),
                        request.version())) {

            throw new IllegalArgumentException(
                    "This record changed after you opened it. "
                            + "Refresh and try again.");
        }

        String number = normalizeEhrNumber(
                request.ehrNumber());

        if (number == null) {
            throw new IllegalArgumentException(
                    "EHR number is required.");
        }

        manualRepository.findByEhrNumber(number)
                .filter(other ->
                        !other.getId().equals(row.getId()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException(
                            "Another manual record already uses that EHR number.");
                });

        apply(row, request, true);

        ManualEhrRecord saved =
                manualRepository.save(row);

        flagEnrolledPatient(
                saved,
                "Manual EHR details were edited and require HIM review.");

        audit(
                saved,
                AuditAction.EHR_MANUAL_RECORD_UPDATED,
                request.reason(),
                "Manual EHR record updated");

        return currentResponse(saved);
    }

    @Transactional
    public ManualEhrRecordResponse sync(
            String publicId,
            ManualEhrSyncRequest request) {

        ManualEhrRecord manual =
                require(publicId);

        EhrVerificationImport active =
                importRepository
                        .findFirstByStatus(ImportStatus.ACTIVE)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "No EHR import is active."));

        EhrVerificationRecord imported =
                imported(
                        active,
                        manual.getEhrNumber());

        if (request.direction()
                == ManualEhrSyncRequest.Direction.FROM_IMPORT) {

            if (imported == null) {
                throw new IllegalArgumentException(
                        "The active import has no row for this EHR number.");
            }

            copy(imported, manual);

        } else {

            boolean created = imported == null;

            if (created) {
                imported = new EhrVerificationRecord();

                imported.setEhrImport(active);

                imported.setEhrNumber(
                        normalizeEhrNumber(
                                manual.getEhrNumber()));
            }

            copy(manual, imported);

            /*
             * copy() deliberately normalizes the EHR number before the
             * record is persisted.
             */
            recordRepository.save(imported);

            if (created) {
                active.setValidRowCount(
                        active.getValidRowCount() + 1);

                active.setRowCount(
                        active.getRowCount() + 1);

                importRepository.save(active);
            }

            flagEnrolledPatient(
                    manual,
                    "Manual EHR details were synchronized to the active import "
                            + "and require HIM review.");
        }

        manual.setLastSyncedAt(
                LocalDateTime.now());

        manual.setLastSyncedBy(
                CurrentUser.usernameOrSystem());

        manual.setLastSyncDirection(
                request.direction().name());

        ManualEhrRecord saved =
                manualRepository.save(manual);

        audit(
                saved,
                AuditAction.EHR_MANUAL_RECORD_SYNCED,
                request.reason(),
                "Manual EHR record synchronized "
                        + request.direction().name());

        return response(
                saved,
                imported(
                        active,
                        saved.getEhrNumber()),
                true);
    }

    /**
     * Manual rows are the governed overlay consulted before the active
     * imported row.
     *
     * EHR numbers are normalized before both lookups so that the same
     * official identifier is used consistently by enrolment, manual
     * records and the imported snapshot.
     */
    @Transactional(readOnly = true)
    public EhrVerificationRecord effectiveRecord(
            Long activeImportId,
            String ehrNumber) {

        String normalized =
                normalizeEhrNumber(ehrNumber);

        if (normalized == null) {
            return null;
        }

        return manualRepository
                .findByEhrNumber(normalized)
                .map(this::asVerificationRecord)
                .orElseGet(() ->
                        recordRepository
                                .findByEhrImportIdAndEhrNumber(
                                        activeImportId,
                                        normalized)
                                .orElse(null));
    }

    private void apply(
            ManualEhrRecord row,
            ManualEhrRecordRequest request,
            boolean editing) {

        LocalDate dob =
                request.dateOfBirth();

        if (dob.isAfter(LocalDate.now())
                || dob.getYear() < 1900) {

            throw new IllegalArgumentException(
                    "Enter a plausible date of birth.");
        }

        String phone =
                EhrImportService.normalisePhone(
                        request.phoneNumber());

        String email =
                normaliseEmail(request.email());

        if (phone == null && email == null) {
            throw new IllegalArgumentException(
                    "Enter a valid phone number or email address for verification.");
        }

        String number =
                normalizeEhrNumber(
                        request.ehrNumber());

        if (number == null) {
            throw new IllegalArgumentException(
                    "EHR number is required.");
        }

        row.setEhrNumber(number);

        row.setFullName(
                request.fullName()
                        .trim()
                        .replaceAll("\\s+", " "));

        row.setDateOfBirthHash(
                Tokens.hash(dob.toString()));

        row.setDateOfBirthMasked(
                "**/**/" + dob.getYear());

        row.setDateOfBirthEncrypted(
                encryptor.encrypt(dob.toString()));

        row.setPhoneHash(
                phone == null
                        ? null
                        : Tokens.hash(phone));

        row.setPhoneMasked(
                phone == null
                        ? null
                        : "*******"
                                + phone.substring(
                                        phone.length() - 4));

        row.setPhoneEncrypted(
                phone == null
                        ? null
                        : encryptor.encrypt(phone));

        row.setEmailHash(
                email == null
                        ? null
                        : Tokens.hash(email));

        row.setEmailMasked(
                email == null
                        ? null
                        : maskEmail(email));

        row.setEmailEncrypted(
                email == null
                        ? null
                        : encryptor.encrypt(email));

        row.setClinic(
                blankToNull(request.clinic()));

        row.setPatientStatus(
                blankToNull(request.patientStatus()));

        row.setIsActiveRecord(
                request.active());

        if (editing) {
            row.setLastSyncedAt(null);
            row.setLastSyncedBy(null);
            row.setLastSyncDirection(null);
        }
    }

    private void copy(
            EhrVerificationRecord from,
            ManualEhrRecord to) {

        String existingPhoneHash =
                to.getPhoneHash();

        String existingPhoneEncrypted =
                to.getPhoneEncrypted();

        /*
         * Always keep the EHR number normalized.
         */
        to.setEhrNumber(
                normalizeEhrNumber(
                        from.getEhrNumber()));

        to.setFullName(
                from.getFullName());

        to.setDateOfBirthHash(
                from.getDateOfBirthHash());

        to.setDateOfBirthMasked(
                from.getDateOfBirthMasked());

        to.setDateOfBirthEncrypted(
                from.getDateOfBirthEncrypted());

        to.setPhoneHash(
                from.getPhoneHash());

        to.setPhoneMasked(
                from.getPhoneMasked());

        /*
         * Imports created before V34 contain only the phone hash and mask.
         * Keep the manual ciphertext when that hash proves it is the same
         * number.
         */
        to.setPhoneEncrypted(
                from.getPhoneEncrypted() != null
                        ? from.getPhoneEncrypted()
                        : Objects.equals(
                                existingPhoneHash,
                                from.getPhoneHash())
                                ? existingPhoneEncrypted
                                : null);

        to.setEmailHash(
                from.getEmailHash());

        to.setEmailMasked(
                from.getEmailMasked());

        to.setEmailEncrypted(
                from.getEmailEncrypted());

        to.setClinic(
                from.getClinic());

        to.setPatientStatus(
                from.getPatientStatus());

        to.setIsActiveRecord(
                from.getIsActiveRecord());
    }

    private void copy(
            ManualEhrRecord from,
            EhrVerificationRecord to) {

        /*
         * Always keep the EHR number normalized when synchronizing
         * a manual record into the active imported snapshot.
         */
        to.setEhrNumber(
                normalizeEhrNumber(
                        from.getEhrNumber()));

        to.setFullName(
                from.getFullName());

        to.setDateOfBirthHash(
                from.getDateOfBirthHash());

        to.setDateOfBirthMasked(
                from.getDateOfBirthMasked());

        to.setDateOfBirthEncrypted(
                from.getDateOfBirthEncrypted());

        to.setPhoneHash(
                from.getPhoneHash());

        to.setPhoneMasked(
                from.getPhoneMasked());

        to.setPhoneEncrypted(
                from.getPhoneEncrypted());

        to.setEmailHash(
                from.getEmailHash());

        to.setEmailMasked(
                from.getEmailMasked());

        to.setEmailEncrypted(
                from.getEmailEncrypted());

        to.setClinic(
                from.getClinic());

        to.setPatientStatus(
                from.getPatientStatus());

        to.setIsActiveRecord(
                from.getIsActiveRecord());
    }

    private EhrVerificationRecord asVerificationRecord(
            ManualEhrRecord manual) {

        EhrVerificationRecord record =
                new EhrVerificationRecord();

        record.setEhrNumber(
                normalizeEhrNumber(
                        manual.getEhrNumber()));

        copy(manual, record);

        return record;
    }

    private ManualEhrRecordResponse currentResponse(
            ManualEhrRecord row) {

        EhrVerificationImport active =
                importRepository
                        .findFirstByStatus(ImportStatus.ACTIVE)
                        .orElse(null);

        return response(
                row,
                imported(
                        active,
                        row.getEhrNumber()),
                active != null);
    }

    private ManualEhrRecordResponse response(
            ManualEhrRecord manual,
            EhrVerificationRecord imported,
            boolean hasActive) {

        String status =
                !hasActive
                        ? "NO_ACTIVE_IMPORT"
                        : imported == null
                                ? "MANUAL_ONLY"
                                : matches(manual, imported)
                                        ? "MATCHED"
                                        : "DIFFERENT";

        return new ManualEhrRecordResponse(
                manual.getPublicId(),
                manual.getVersion(),
                manual.getEhrNumber(),
                manual.getFullName(),
                LocalDate.parse(
                        encryptor.decrypt(
                                manual.getDateOfBirthEncrypted())),
                decrypt(
                        manual.getPhoneEncrypted()),
                decrypt(
                        manual.getEmailEncrypted()),
                manual.getClinic(),
                manual.getPatientStatus(),
                Boolean.TRUE.equals(
                        manual.getIsActiveRecord()),
                status,
                manual.getLastSyncedAt(),
                manual.getLastSyncedBy(),
                manual.getLastSyncDirection(),
                manual.getUpdatedAt(),
                manual.getUpdatedBy());
    }

    private boolean matches(
            ManualEhrRecord a,
            EhrVerificationRecord b) {

        return normalizeEhrNumber(a.getEhrNumber())
                .equals(normalizeEhrNumber(b.getEhrNumber()))

                && a.getFullName()
                        .equalsIgnoreCase(b.getFullName())

                && Objects.equals(
                        a.getDateOfBirthHash(),
                        b.getDateOfBirthHash())

                && Objects.equals(
                        a.getPhoneHash(),
                        b.getPhoneHash())

                && Objects.equals(
                        a.getEmailHash(),
                        b.getEmailHash())

                && Objects.equals(
                        a.getClinic(),
                        b.getClinic())

                && Objects.equals(
                        a.getPatientStatus(),
                        b.getPatientStatus())

                && Objects.equals(
                        a.getIsActiveRecord(),
                        b.getIsActiveRecord());
    }

    private EhrVerificationRecord imported(
            EhrVerificationImport active,
            String ehr) {

        if (active == null) {
            return null;
        }

        String normalized =
                normalizeEhrNumber(ehr);

        if (normalized == null) {
            return null;
        }

        return recordRepository
                .findByEhrImportIdAndEhrNumber(
                        active.getId(),
                        normalized)
                .orElse(null);
    }

    private ManualEhrRecord require(
            String id) {

        return manualRepository
                .findByPublicId(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "No such manual EHR record."));
    }

    private String decrypt(String value) {
        return value == null
                ? null
                : encryptor.decrypt(value);
    }

    private String normaliseEmail(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String v =
                value.trim()
                        .toLowerCase(Locale.ROOT);

        return v.contains("@")
                && !v.endsWith("@")
                ? v
                : null;
    }

    private String maskEmail(String value) {
        int at =
                value.indexOf('@');

        String local =
                value.substring(0, at);

        return (local.length() <= 2
                ? local.substring(0, 1)
                : local.substring(0, 2))
                + "***"
                + value.substring(at);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    /**
     * Normalizes an EHR number without altering the actual identifier.
     *
     * Only surrounding whitespace and letter case are normalized.
     *
     * We deliberately do NOT:
     * - strip punctuation,
     * - remove prefixes,
     * - remove leading zeroes,
     * - change separators.
     */
    private String normalizeEhrNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim()
                .toUpperCase(Locale.ROOT);
    }

    private void flagEnrolledPatient(
            ManualEhrRecord row,
            String details) {

        String ehrNumber =
                normalizeEhrNumber(
                        row.getEhrNumber());

        if (ehrNumber == null) {
            return;
        }

        patientRepository
                .findByEhrNumber(ehrNumber)
                .ifPresent(patient -> {
                    patient.setDriftFlagged(true);
                    patient.setDriftFlaggedAt(
                            LocalDateTime.now());
                    patient.setDriftDetails(details);
                    patientRepository.save(patient);
                });
    }

    private void audit(
            ManualEhrRecord row,
            AuditAction action,
            String reason,
            String details) {

        auditService.record(
                AuditService.AuditEvent.builder()
                        .action(action)
                        .entityType("ManualEhrRecord")
                        .entityId(row.getId())
                        .details(
                                details
                                        + " for "
                                        + row.getEhrNumber())
                        .reason(reason)
                        .build());
    }
}