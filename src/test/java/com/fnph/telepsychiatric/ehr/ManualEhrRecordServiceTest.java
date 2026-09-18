package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.ehr.api.ManualEhrRecordRequest;
import com.fnph.telepsychiatric.ehr.api.ManualEhrSyncRequest;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.crypto.SecretEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManualEhrRecordServiceTest {
    @Mock ManualEhrRecordRepository manualRepository;
    @Mock EhrImportRepository importRepository;
    @Mock EhrRecordRepository recordRepository;
    @Mock PatientRepository patientRepository;
    @Mock AuditService auditService;
    ManualEhrRecordService service;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        service = new ManualEhrRecordService(manualRepository, importRepository, recordRepository,
                patientRepository, new SecretEncryptor(key), auditService);
        lenient().when(manualRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsEncryptedManualRecordAndReportsMissingActiveImport() {
        when(manualRepository.findByEhrNumber("204815")).thenReturn(Optional.empty());
        when(importRepository.findFirstByStatus(ImportStatus.ACTIVE)).thenReturn(Optional.empty());

        var response = service.create(request());

        assertThat(response.ehrNumber()).isEqualTo("204815");
        assertThat(response.dateOfBirth()).isEqualTo(LocalDate.of(1990, 4, 12));
        assertThat(response.syncStatus()).isEqualTo("NO_ACTIVE_IMPORT");
        verify(manualRepository).save(argThat(row ->
                !row.getDateOfBirthEncrypted().contains("1990-04-12")
                        && row.getPhoneHash() != null && row.getEmailHash() != null));
    }

    @Test
    void pushesManualOnlyRecordIntoActiveImport() {
        ManualEhrRecord manual = new ManualEhrRecord();
        ReflectionTestUtils.setField(manual, "id", 22L);
        ReflectionTestUtils.setField(manual, "publicId", "MEHR-1");
        applyStoredValues(manual);
        EhrVerificationImport active = new EhrVerificationImport();
        ReflectionTestUtils.setField(active, "id", 9L);
        active.setValidRowCount(3); active.setRowCount(3);
        when(manualRepository.findByPublicId("MEHR-1")).thenReturn(Optional.of(manual));
        when(importRepository.findFirstByStatus(ImportStatus.ACTIVE)).thenReturn(Optional.of(active));
        when(recordRepository.findByEhrImportIdAndEhrNumber(9L, "204815")).thenReturn(Optional.empty(), Optional.of(new EhrVerificationRecord()));
        when(patientRepository.findByEhrNumber("204815")).thenReturn(Optional.empty());

        service.sync("MEHR-1", new ManualEhrSyncRequest(ManualEhrSyncRequest.Direction.TO_IMPORT, "Verified hospital card"));

        verify(recordRepository).save(argThat(row -> row.getEhrImport() == active && row.getEhrNumber().equals("204815")));
        assertThat(active.getValidRowCount()).isEqualTo(4);
        assertThat(manual.getLastSyncDirection()).isEqualTo("TO_IMPORT");
    }

    private ManualEhrRecordRequest request() {
        return new ManualEhrRecordRequest("204815", "Prototype Patient", LocalDate.of(1990, 4, 12),
                "08031234567", "patient@example.test", "General Adult Clinic", "ACTIVE", true,
                "Verified hospital card", null);
    }

    private void applyStoredValues(ManualEhrRecord row) {
        ManualEhrRecordRequest input = request();
        when(manualRepository.findByEhrNumber("204815")).thenReturn(Optional.empty());
        ManualEhrRecord created = new ManualEhrRecord();
        // Use create to exercise the same hashing/encryption path, then copy its fields.
        when(importRepository.findFirstByStatus(ImportStatus.ACTIVE)).thenReturn(Optional.empty());
        service.create(input);
        verify(manualRepository).save(argThat(saved -> { copy(saved, row); return true; }));
        clearInvocations(manualRepository, importRepository, auditService);
    }

    private void copy(ManualEhrRecord from, ManualEhrRecord to) {
        to.setEhrNumber(from.getEhrNumber()); to.setFullName(from.getFullName());
        to.setDateOfBirthHash(from.getDateOfBirthHash()); to.setDateOfBirthMasked(from.getDateOfBirthMasked()); to.setDateOfBirthEncrypted(from.getDateOfBirthEncrypted());
        to.setPhoneHash(from.getPhoneHash()); to.setPhoneMasked(from.getPhoneMasked()); to.setPhoneEncrypted(from.getPhoneEncrypted());
        to.setEmailHash(from.getEmailHash()); to.setEmailMasked(from.getEmailMasked()); to.setEmailEncrypted(from.getEmailEncrypted());
        to.setClinic(from.getClinic()); to.setPatientStatus(from.getPatientStatus()); to.setIsActiveRecord(true);
    }
}
