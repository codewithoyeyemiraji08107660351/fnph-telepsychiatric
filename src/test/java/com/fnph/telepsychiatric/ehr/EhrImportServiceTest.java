package com.fnph.telepsychiatric.ehr;

import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.crypto.SecretEncryptor;
import com.fnph.telepsychiatric.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EhrImportServiceTest {
    private final EhrImportRepository imports = mock(EhrImportRepository.class);
    private final EhrRecordRepository records = mock(EhrRecordRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final EhrImportService service = new EhrImportService(imports, records,
            mock(PatientRepository.class), mock(UserRepository.class), audit,
            mock(SecretEncryptor.class));

    @BeforeEach
    void assignImportId() {
        when(imports.save(any(EhrVerificationImport.class))).thenAnswer(call -> {
            EhrVerificationImport value = call.getArgument(0);
            if (value.getId() == null) value.setId(2L);
            return value;
        });
    }

    @Test
    void successfulUploadImmediatelyReplacesTheActiveSnapshot() {
        EhrVerificationImport previous = new EhrVerificationImport();
        previous.setId(1L);
        previous.setStatus(ImportStatus.ACTIVE);
        when(imports.findFirstByStatus(ImportStatus.ACTIVE)).thenReturn(Optional.of(previous));

        EhrVerificationImport result = upload("1001,Test Patient,1990-01-01\n");

        assertThat(result.getStatus()).isEqualTo(ImportStatus.ACTIVE);
        assertThat(result.getActivatedAt()).isNotNull();
        assertThat(result.getActivatedBy()).isEqualTo("system");
        assertThat(result.getValidRowCount()).isEqualTo(1);
        assertThat(previous.getStatus()).isEqualTo(ImportStatus.SUPERSEDED);
        assertThat(previous.getSupersededByImportId()).isEqualTo(result.getId());
        assertThat(previous.getSupersededAt()).isNotNull();
        verify(records).findDriftAgainstActivePatients(result.getId());
        verify(audit).record(any(AuditService.AuditEvent.class));
    }

    @Test
    void partiallyValidUploadActivatesUsableRowsAndReportsSkippedRows() {
        EhrVerificationImport result = upload("1001,Test Patient,1990-01-01\n1002,Other Patient,not-a-date\n");

        assertThat(result.getStatus()).isEqualTo(ImportStatus.ACTIVE);
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.getValidRowCount()).isEqualTo(1);
        assertThat(result.getRejectedRowCount()).isEqualTo(1);
        assertThat(result.getValidationReport()).contains("not-a-date");
        verify(records, times(1)).save(any(EhrVerificationRecord.class));
    }

    @Test
    void rejectedUploadDoesNotReplaceTheCurrentSnapshot() {
        EhrVerificationImport result = upload("1001,Test Patient,not-a-date\n");

        assertThat(result.getStatus()).isEqualTo(ImportStatus.REJECTED);
        assertThat(result.getActivatedAt()).isNull();
        verify(imports, never()).findFirstByStatus(ImportStatus.ACTIVE);
        verifyNoInteractions(records, audit);
    }

    private EhrVerificationImport upload(String rows) {
        MockMultipartFile file = new MockMultipartFile("file", "patients.csv", "text/csv",
                ("ehr_number,full_name,date_of_birth\n" + rows).getBytes(StandardCharsets.UTF_8));
        return service.upload(file, LocalDate.of(2026, 1, 1));
    }
}
