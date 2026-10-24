package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.center.CentreStatus;
import com.fnph.telepsychiatric.common.BusinessRuleException;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A returned referral can be corrected and sent again; a suspended centre
 * cannot send one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CentreReferralEditTest {

    @Mock CentreReferralRepository referralRepository;
    @Mock CentreBundleReceiptRepository receiptRepository;
    @Mock CentreBundleActionRepository actionRepository;
    @Mock CentreWalletService walletService;
    @Mock InAppNotificationService notifications;
    @Mock AuditService auditService;
    @Mock TriageService triageService;

    CentreReferralService service;
    Center centre;
    CentreReferral referral;

    @BeforeEach
    void setUp() {
        service = new CentreReferralService(referralRepository, receiptRepository, actionRepository,
                walletService, notifications, auditService, triageService);
        centre = new Center();
        centre.setId(7L);
        centre.setStatus(CentreStatus.ACTIVE);
        referral = new CentreReferral();
        referral.setId(5L);
        referral.setCentre(centre);
        referral.setReference("REF-1");
        referral.setReferralReason("Low mood for three months, not sleeping");
        referral.setUrgency(ReferralUrgency.ROUTINE);
        TenantContext.set(TenantScope.centre(7L));
        when(referralRepository.findByCentreIdAndPublicId(7L, "R-1")).thenReturn(Optional.of(referral));
        when(referralRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private CentreReferralService.ReferralDetails details(ReferralUrgency urgency) {
        return new CentreReferralService.ReferralDetails(
                "Low mood for three months, not sleeping, stopped working",
                "PHQ-9 of 19", null, "Sertraline 50 mg since August", null, urgency);
    }

    @Test
    @DisplayName("01 a returned referral takes what FNPH asked for")
    void returnedIsEditable() {
        referral.setStatus(ReferralStatus.RETURNED);

        service.update("R-1", details(ReferralUrgency.SOON));

        assertThat(referral.getRelevantMedicines()).isEqualTo("Sertraline 50 mg since August");
        assertThat(referral.getUrgency()).isEqualTo(ReferralUrgency.SOON);
        // Still returned: it goes back to FNPH through consent, not by editing.
        assertThat(referral.getStatus()).isEqualTo(ReferralStatus.RETURNED);
    }

    @Test
    @DisplayName("02 no urgency given keeps the one already set")
    void urgencyKept() {
        referral.setStatus(ReferralStatus.DRAFT);
        referral.setUrgency(ReferralUrgency.SOON);

        service.update("R-1", details(null));

        assertThat(referral.getUrgency()).isEqualTo(ReferralUrgency.SOON);
    }

    @Test
    @DisplayName("03 a sent referral cannot be rewritten under the doctor reading it")
    void submittedIsLocked() {
        referral.setStatus(ReferralStatus.SUBMITTED);

        assertThatThrownBy(() -> service.update("R-1", details(null)))
                .isInstanceOf(BusinessRuleException.class);
        verify(referralRepository, never()).save(any());
    }

    @Test
    @DisplayName("04 a suspended centre cannot send a draft it wrote while active")
    void suspendedCannotSubmit() {
        referral.setStatus(ReferralStatus.DRAFT);
        centre.setStatus(CentreStatus.SUSPENDED);

        assertThatThrownBy(() -> service.submit("R-1", "v1", "Nurse Bello"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not active");
        verify(referralRepository, never()).save(any());
    }
}
