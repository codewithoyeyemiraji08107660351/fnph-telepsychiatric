package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The centre session clock: who was absent decides the outcome, and an
 * appointment nobody joined does not stay APPROVED forever.
 */
@ExtendWith(MockitoExtension.class)
class CentreConsultationClockStepsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);

    @Mock CentreConsultationRepository consultationRepository;
    @Mock CentreAppointmentRepository appointmentRepository;
    @Mock AttendanceEventRepository attendanceRepository;
    @Mock InAppNotificationService notifications;
    @Mock com.fnph.telepsychiatric.centre.CentreWalletService walletService;
    @Mock com.fnph.telepsychiatric.centre.CentreReferralRepository referralRepository;

    CentreConsultationClockSteps steps;
    CentreAppointment appointment;
    CentreConsultation consultation;
    com.fnph.telepsychiatric.centre.CentreReferral referral;

    @BeforeEach
    void setUp() {
        steps = new CentreConsultationClockSteps(consultationRepository, appointmentRepository,
                attendanceRepository, notifications, walletService, referralRepository);

        Center centre = new Center();
        centre.setId(7L);
        centre.setName("Zaria Centre");

        appointment = new CentreAppointment();
        appointment.setId(11L);
        appointment.setCentre(centre);
        appointment.setReference("CAP-TEST");
        appointment.setStatus(Status.IN_PROGRESS);
        appointment.setScheduledEndAt(NOW.minusMinutes(1));
        referral = new com.fnph.telepsychiatric.centre.CentreReferral();
        referral.setStatus(com.fnph.telepsychiatric.centre.ReferralStatus.SCHEDULED);
        appointment.setReferral(referral);

        consultation = new CentreConsultation();
        consultation.setId(21L);
        consultation.setCentreAppointment(appointment);
        consultation.setScheduledStartAt(NOW.minusMinutes(31));
        consultation.setScheduledEndAt(NOW.minusMinutes(1));
        consultation.setRoomName("centre-cap-test");
    }

    private void centreJoined(boolean joined) {
        when(attendanceRepository.existsByCentreConsultationIdAndParticipantRoleAndEventType(
                21L, ParticipantRole.CENTRE, AttendanceEventType.JOINED)).thenReturn(joined);
    }

    @Test
    @DisplayName("01 both parties joined: completed, room queued for deletion")
    void bothJoined() {
        consultation.setStartedAt(NOW.minusMinutes(30));
        when(consultationRepository.findRunning(NOW)).thenReturn(List.of(consultation));
        centreJoined(true);

        var result = steps.advanceRunning(NOW, 5, 1);

        assertThat(result.ended()).isEqualTo(1);
        assertThat(result.roomsToDelete()).extracting(ConsultationClockSteps.RoomRef::roomName)
                .containsExactly("centre-cap-test");
        assertThat(consultation.getOutcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(consultation.getEndedAt()).isEqualTo(NOW);
        assertThat(appointment.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(referral.getStatus()).isEqualTo(com.fnph.telepsychiatric.centre.ReferralStatus.COMPLETED);
        verify(notifications, never()).notifyRole(anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("02 doctor joined, centre did not: no-show recorded against the centre")
    void centreAbsent() {
        consultation.setStartedAt(NOW.minusMinutes(30));
        when(consultationRepository.findRunning(NOW)).thenReturn(List.of(consultation));
        centreJoined(false);

        steps.advanceRunning(NOW, 5, 1);

        assertThat(consultation.getOutcome()).isEqualTo(Outcome.NO_SHOW);
        assertThat(appointment.getStatus()).isEqualTo(Status.NO_SHOW);
        assertThat(appointment.getNoShowReason()).isEqualTo(CentreConsultationClockSteps.CENTRE_ABSENT);
        // Missed: the referral reopens so another time can be requested.
        assertThat(referral.getStatus()).isEqualTo(com.fnph.telepsychiatric.centre.ReferralStatus.SUBMITTED);
        // The centre did not turn up for a doctor who did: the charge stands.
        verify(walletService, never()).reverseBookingDebit(any(), anyString());
        verify(notifications).notifyRole(eq("HUB_COORDINATOR"), isNull(), eq(NotificationType.NO_SHOW_RECORDED),
                anyString(), anyString(), anyString(), eq("CentreAppointment"), eq(11L));
    }

    @Test
    @DisplayName("03 doctor never joined: session abandoned, charge returned, the hub is told")
    void doctorAbsent() {
        when(consultationRepository.findRunning(NOW)).thenReturn(List.of(consultation));
        centreJoined(true);
        var reversal = new com.fnph.telepsychiatric.payment.WalletTransaction();
        reversal.setId(99L);
        when(walletService.reverseBookingDebit(eq(appointment), anyString())).thenReturn(reversal);

        steps.advanceRunning(NOW, 5, 1);

        assertThat(appointment.getWalletReversalId()).isEqualTo(99L);

        assertThat(consultation.getOutcome()).isEqualTo(Outcome.ABANDONED);
        assertThat(appointment.getStatus()).isEqualTo(Status.NO_SHOW);
        assertThat(appointment.getNoShowReason()).isEqualTo(CentreConsultationClockSteps.DOCTOR_ABSENT);
        verify(notifications).notifyRole(eq("CENTRE_HUB_COORDINATOR"), eq(appointment.getCentre()),
                eq(NotificationType.NO_SHOW_RECORDED), anyString(), anyString(), anyString(),
                eq("CentreAppointment"), eq(11L));
    }

    @Test
    @DisplayName("04 still running: warnings fire once, nothing closes")
    void warnings() {
        consultation.setStartedAt(NOW.minusMinutes(20));
        consultation.setScheduledEndAt(NOW.plusMinutes(4));
        when(consultationRepository.findRunning(NOW)).thenReturn(List.of(consultation));

        var result = steps.advanceRunning(NOW, 5, 1);

        assertThat(result.ended()).isZero();
        assertThat(consultation.getWarningOneSentAt()).isEqualTo(NOW);
        assertThat(consultation.getWarningTwoSentAt()).isNull();
        assertThat(consultation.getEndedAt()).isNull();
    }

    @Test
    @DisplayName("05 an approved appointment nobody joined is closed as a no-show and refunded")
    void unattended() {
        appointment.setStatus(Status.APPROVED);
        when(appointmentRepository.findApprovedEndedWithoutSession(NOW)).thenReturn(List.of(appointment));

        var reversal = new com.fnph.telepsychiatric.payment.WalletTransaction();
        reversal.setId(98L);
        when(walletService.reverseBookingDebit(eq(appointment), anyString())).thenReturn(reversal);

        int closed = steps.closeUnattended(NOW);

        assertThat(appointment.getWalletReversalId()).isEqualTo(98L);

        assertThat(closed).isEqualTo(1);
        assertThat(appointment.getStatus()).isEqualTo(Status.NO_SHOW);
        assertThat(appointment.getNoShowReason()).isEqualTo(CentreConsultationClockSteps.NOBODY_JOINED);
        assertThat(appointment.getNoShowAt()).isEqualTo(NOW);
    }
}
