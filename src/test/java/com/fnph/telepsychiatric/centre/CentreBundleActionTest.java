package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.tenancy.TenantContext;
import com.fnph.telepsychiatric.tenancy.TenantScope;
import com.fnph.telepsychiatric.triage.TriageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Each centre service records its own action on a bundle, and only its own.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CentreBundleActionTest {

    @Mock CentreReferralRepository referralRepository;
    @Mock CentreBundleReceiptRepository receiptRepository;
    @Mock CentreBundleActionRepository actionRepository;
    @Mock CentreWalletService walletService;
    @Mock InAppNotificationService notifications;
    @Mock AuditService auditService;
    @Mock TriageService triageService;

    CentreReferralService service;
    CentreBundleReceipt receipt;

    @BeforeEach
    void setUp() {
        service = new CentreReferralService(referralRepository, receiptRepository, actionRepository,
                walletService, notifications, auditService, triageService);
        Center centre = new Center();
        centre.setId(7L);
        centre.setCode("ZAR");
        receipt = new CentreBundleReceipt();
        receipt.setId(31L);
        receipt.setCentre(centre);
        TenantContext.set(TenantScope.centre(7L));
        when(receiptRepository.findByCentreIdAndPublicId(7L, "RC-1")).thenReturn(Optional.of(receipt));
        when(actionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void signedInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("staff", "x", "ROLE_" + role, "centre_bundle.mark_treated"));
    }

    @Test
    @DisplayName("01 the pharmacy records its own action and the bundle stays open for the others")
    void pharmacyActs() {
        signedInAs("CENTRE_PHARMACY");
        when(actionRepository.findByCentreIdAndReceiptIdAndCapability(7L, 31L, CentreCapability.PHARMACY))
                .thenReturn(Optional.empty());

        CentreBundleAction action = service.markTreated("RC-1", "Sertraline 50 mg dispensed", CentreCapability.PHARMACY);

        assertThat(action.getCapability()).isEqualTo(CentreCapability.PHARMACY);
        assertThat(action.getNotes()).isEqualTo("Sertraline 50 mg dispensed");
        // The coordinator's close, and so the laboratory's and records' queues, are untouched.
        assertThat(receipt.getTreatedAt()).isNull();
    }

    @Test
    @DisplayName("02 a pharmacist cannot record the laboratory's action")
    void wrongService() {
        signedInAs("CENTRE_PHARMACY");

        assertThatThrownBy(() -> service.markTreated("RC-1", "Bloods taken", CentreCapability.LABORATORY))
                .isInstanceOf(AccessDeniedException.class);
        verify(actionRepository, never()).save(any());
    }

    @Test
    @DisplayName("03 the coordinator's action also closes the bundle for the centre")
    void coordinatorCloses() {
        signedInAs("CENTRE_HUB_COORDINATOR");
        when(actionRepository.findByCentreIdAndReceiptIdAndCapability(7L, 31L, CentreCapability.COORDINATION))
                .thenReturn(Optional.empty());

        service.markTreated("RC-1", "Care plan explained, follow-up booked", CentreCapability.COORDINATION);

        assertThat(receipt.getTreatedAt()).isNotNull();
        assertThat(receipt.getTreatmentNotes()).isEqualTo("Care plan explained, follow-up booked");
    }

    @Test
    @DisplayName("04 recording twice returns the first record")
    void idempotent() {
        signedInAs("CENTRE_LABORATORY");
        CentreBundleAction earlier = new CentreBundleAction();
        when(actionRepository.findByCentreIdAndReceiptIdAndCapability(7L, 31L, CentreCapability.LABORATORY))
                .thenReturn(Optional.of(earlier));

        assertThat(service.markTreated("RC-1", "again", CentreCapability.LABORATORY)).isSameAs(earlier);
        verify(actionRepository, never()).save(any());
    }
}
