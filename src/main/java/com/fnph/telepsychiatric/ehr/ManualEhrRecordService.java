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
        EhrVerificationImport active = importRepository.findFirstByStatus(ImportStatus.ACTIVE).orElse(null);
        return manualRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(row -> response(row, imported(active, row.getEhrNumber()), active != null)).toList();
    }

    @Transactional
    public ManualEhrRecordResponse create(ManualEhrRecordRequest request) {
        String number = request.ehrNumber().trim();
        if (manualRepository.findByEhrNumber(number).isPresent()) {
            throw new IllegalArgumentException("A manual EHR record already exists for " + number + ". Edit that record instead.");
        }
        ManualEhrRecord row = new ManualEhrRecord();
        apply(row, request, false);
        ManualEhrRecord saved = manualRepository.save(row);
        audit(saved, AuditAction.EHR_MANUAL_RECORD_CREATED, request.reason(), "Manual EHR record created");
        return currentResponse(saved);
    }

    @Transactional
    public ManualEhrRecordResponse update(String publicId, ManualEhrRecordRequest request) {
        ManualEhrRecord row = require(publicId);
        if (request.version() == null || !Objects.equals(row.getVersion(), request.version())) {
            throw new IllegalArgumentException("This record changed after you opened it. Refresh and try again.");
        }
        String number = request.ehrNumber().trim();
        manualRepository.findByEhrNumber(number)
                .filter(other -> !other.getId().equals(row.getId()))
                .ifPresent(other -> { throw new IllegalArgumentException("Another manual record already uses that EHR number."); });
        apply(row, request, true);
        ManualEhrRecord saved = manualRepository.save(row);
        flagEnrolledPatient(saved, "Manual EHR details were edited and require HIM review.");
        audit(saved, AuditAction.EHR_MANUAL_RECORD_UPDATED, request.reason(), "Manual EHR record updated");
        return currentResponse(saved);
    }

    @Transactional
    public ManualEhrRecordResponse sync(String publicId, ManualEhrSyncRequest request) {
        ManualEhrRecord manual = require(publicId);
        EhrVerificationImport active = importRepository.findFirstByStatus(ImportStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("No EHR import is active."));
        EhrVerificationRecord imported = imported(active, manual.getEhrNumber());

        if (request.direction() == ManualEhrSyncRequest.Direction.FROM_IMPORT) {
            if (imported == null) throw new IllegalArgumentException("The active import has no row for this EHR number.");
            copy(imported, manual);
        } else {
            boolean created = imported == null;
            if (created) {
                imported = new EhrVerificationRecord();
                imported.setEhrImport(active);
                imported.setEhrNumber(manual.getEhrNumber());
            }
            copy(manual, imported);
            recordRepository.save(imported);
            if (created) {
                active.setValidRowCount(active.getValidRowCount() + 1);
                active.setRowCount(active.getRowCount() + 1);
                importRepository.save(active);
            }
            flagEnrolledPatient(manual, "Manual EHR details were synchronized to the active import and require HIM review.");
        }
        manual.setLastSyncedAt(LocalDateTime.now());
        manual.setLastSyncedBy(CurrentUser.usernameOrSystem());
        manual.setLastSyncDirection(request.direction().name());
        ManualEhrRecord saved = manualRepository.save(manual);
        audit(saved, AuditAction.EHR_MANUAL_RECORD_SYNCED, request.reason(),
                "Manual EHR record synchronized " + request.direction().name());
        return response(saved, imported(active, saved.getEhrNumber()), true);
    }

    /** Manual rows are the governed overlay consulted before the active imported row. */
    @Transactional(readOnly = true)
    public EhrVerificationRecord effectiveRecord(Long activeImportId, String ehrNumber) {
        return manualRepository.findByEhrNumber(ehrNumber)
                .map(this::asVerificationRecord)
                .orElseGet(() -> recordRepository.findByEhrImportIdAndEhrNumber(activeImportId, ehrNumber).orElse(null));
    }

    private void apply(ManualEhrRecord row, ManualEhrRecordRequest request, boolean editing) {
        LocalDate dob = request.dateOfBirth();
        if (dob.isAfter(LocalDate.now()) || dob.getYear() < 1900) throw new IllegalArgumentException("Enter a plausible date of birth.");
        String phone = EhrImportService.normalisePhone(request.phoneNumber());
        String email = normaliseEmail(request.email());
        if (phone == null && email == null) throw new IllegalArgumentException("Enter a valid phone number or email address for verification.");
        row.setEhrNumber(request.ehrNumber().trim());
        row.setFullName(request.fullName().trim().replaceAll("\\s+", " "));
        row.setDateOfBirthHash(Tokens.hash(dob.toString()));
        row.setDateOfBirthMasked("**/**/" + dob.getYear());
        row.setDateOfBirthEncrypted(encryptor.encrypt(dob.toString()));
        row.setPhoneHash(phone == null ? null : Tokens.hash(phone));
        row.setPhoneMasked(phone == null ? null : "*******" + phone.substring(phone.length() - 4));
        row.setPhoneEncrypted(phone == null ? null : encryptor.encrypt(phone));
        row.setEmailHash(email == null ? null : Tokens.hash(email));
        row.setEmailMasked(email == null ? null : maskEmail(email));
        row.setEmailEncrypted(email == null ? null : encryptor.encrypt(email));
        row.setClinic(blankToNull(request.clinic()));
        row.setPatientStatus(blankToNull(request.patientStatus()));
        row.setIsActiveRecord(request.active());
        if (editing) {
            row.setLastSyncedAt(null);
            row.setLastSyncedBy(null);
            row.setLastSyncDirection(null);
        }
    }

    private void copy(EhrVerificationRecord from, ManualEhrRecord to) {
        String existingPhoneHash = to.getPhoneHash();
        String existingPhoneEncrypted = to.getPhoneEncrypted();
        to.setFullName(from.getFullName()); to.setDateOfBirthHash(from.getDateOfBirthHash());
        to.setDateOfBirthMasked(from.getDateOfBirthMasked()); to.setDateOfBirthEncrypted(from.getDateOfBirthEncrypted());
        to.setPhoneHash(from.getPhoneHash()); to.setPhoneMasked(from.getPhoneMasked());
        // Imports created before V34 contain only the phone hash and mask. Keep
        // the manual ciphertext when that hash proves it is the same number.
        to.setPhoneEncrypted(from.getPhoneEncrypted() != null ? from.getPhoneEncrypted()
                : Objects.equals(existingPhoneHash, from.getPhoneHash()) ? existingPhoneEncrypted : null);
        to.setEmailHash(from.getEmailHash()); to.setEmailMasked(from.getEmailMasked()); to.setEmailEncrypted(from.getEmailEncrypted());
        to.setClinic(from.getClinic()); to.setPatientStatus(from.getPatientStatus()); to.setIsActiveRecord(from.getIsActiveRecord());
    }

    private void copy(ManualEhrRecord from, EhrVerificationRecord to) {
        to.setFullName(from.getFullName()); to.setDateOfBirthHash(from.getDateOfBirthHash());
        to.setDateOfBirthMasked(from.getDateOfBirthMasked()); to.setDateOfBirthEncrypted(from.getDateOfBirthEncrypted());
        to.setPhoneHash(from.getPhoneHash()); to.setPhoneMasked(from.getPhoneMasked()); to.setPhoneEncrypted(from.getPhoneEncrypted());
        to.setEmailHash(from.getEmailHash()); to.setEmailMasked(from.getEmailMasked()); to.setEmailEncrypted(from.getEmailEncrypted());
        to.setClinic(from.getClinic()); to.setPatientStatus(from.getPatientStatus()); to.setIsActiveRecord(from.getIsActiveRecord());
    }

    private EhrVerificationRecord asVerificationRecord(ManualEhrRecord m) {
        EhrVerificationRecord r = new EhrVerificationRecord();
        r.setEhrNumber(m.getEhrNumber()); copy(m, r); return r;
    }

    private ManualEhrRecordResponse currentResponse(ManualEhrRecord row) {
        EhrVerificationImport active = importRepository.findFirstByStatus(ImportStatus.ACTIVE).orElse(null);
        return response(row, imported(active, row.getEhrNumber()), active != null);
    }

    private ManualEhrRecordResponse response(ManualEhrRecord m, EhrVerificationRecord imported, boolean hasActive) {
        String status = !hasActive ? "NO_ACTIVE_IMPORT" : imported == null ? "MANUAL_ONLY" : matches(m, imported) ? "MATCHED" : "DIFFERENT";
        return new ManualEhrRecordResponse(m.getPublicId(), m.getVersion(), m.getEhrNumber(), m.getFullName(),
                LocalDate.parse(encryptor.decrypt(m.getDateOfBirthEncrypted())), decrypt(m.getPhoneEncrypted()), decrypt(m.getEmailEncrypted()),
                m.getClinic(), m.getPatientStatus(), Boolean.TRUE.equals(m.getIsActiveRecord()), status,
                m.getLastSyncedAt(), m.getLastSyncedBy(), m.getLastSyncDirection(), m.getUpdatedAt(), m.getUpdatedBy());
    }

    private boolean matches(ManualEhrRecord a, EhrVerificationRecord b) {
        return a.getFullName().equalsIgnoreCase(b.getFullName()) && Objects.equals(a.getDateOfBirthHash(), b.getDateOfBirthHash())
                && Objects.equals(a.getPhoneHash(), b.getPhoneHash()) && Objects.equals(a.getEmailHash(), b.getEmailHash())
                && Objects.equals(a.getClinic(), b.getClinic()) && Objects.equals(a.getPatientStatus(), b.getPatientStatus())
                && Objects.equals(a.getIsActiveRecord(), b.getIsActiveRecord());
    }

    private EhrVerificationRecord imported(EhrVerificationImport active, String ehr) {
        return active == null ? null : recordRepository.findByEhrImportIdAndEhrNumber(active.getId(), ehr).orElse(null);
    }
    private ManualEhrRecord require(String id) { return manualRepository.findByPublicId(id).orElseThrow(() -> new IllegalArgumentException("No such manual EHR record.")); }
    private String decrypt(String value) { return value == null ? null : encryptor.decrypt(value); }
    private String normaliseEmail(String value) { if (value == null || value.isBlank()) return null; String v=value.trim().toLowerCase(Locale.ROOT); return v.contains("@") && !v.endsWith("@") ? v : null; }
    private String maskEmail(String value) { int at=value.indexOf('@'); String local=value.substring(0,at); return (local.length()<=2?local.substring(0,1):local.substring(0,2))+"***"+value.substring(at); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private void flagEnrolledPatient(ManualEhrRecord row, String details) { patientRepository.findByEhrNumber(row.getEhrNumber()).ifPresent(p -> { p.setDriftFlagged(true); p.setDriftFlaggedAt(LocalDateTime.now()); p.setDriftDetails(details); patientRepository.save(p); }); }
    private void audit(ManualEhrRecord row, AuditAction action, String reason, String details) { auditService.record(AuditService.AuditEvent.builder().action(action).entityType("ManualEhrRecord").entityId(row.getId()).details(details + " for " + row.getEhrNumber()).reason(reason).build()); }
}
