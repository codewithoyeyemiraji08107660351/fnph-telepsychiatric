package com.fnph.telepsychiatric.centre;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.appointment.CentreAppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.hub.HubTeamService;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.patient.CentrePatientRepository;
import com.fnph.telepsychiatric.payment.LedgerDirection;
import com.fnph.telepsychiatric.payment.LedgerEntryStatus;
import com.fnph.telepsychiatric.payment.Wallet;
import com.fnph.telepsychiatric.payment.WalletRepository;
import com.fnph.telepsychiatric.payment.WalletTransaction;
import com.fnph.telepsychiatric.payment.WalletTransactionRepository;
import com.fnph.telepsychiatric.scheduling.DoctorAvailabilityRepository;
import com.fnph.telepsychiatric.scheduling.DoctorCalendar;
import com.fnph.telepsychiatric.scheduling.RoomRepository;
import com.fnph.telepsychiatric.scheduling.Slot;
import com.fnph.telepsychiatric.scheduling.SlotRepository;
import com.fnph.telepsychiatric.scheduling.SlotState;
import com.fnph.telepsychiatric.user.Users;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where a centre's money goes when a consultation does not happen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CentreBookingMoneyTest {

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository ledgerRepository;
    @Mock WalletAlertRepository alertRepository;
    @Mock ConfigurationService configuration;
    @Mock InAppNotificationService notifications;
    @Mock AuditService auditService;

    @Mock CentrePatientRepository patientRepository;
    @Mock CentreReferralRepository referralRepository;
    @Mock CentreAppointmentRepository appointmentRepository;
    @Mock SlotRepository slotRepository;
    @Mock RoomRepository roomRepository;
    @Mock CentreWalletService mockWallet;
    @Mock DoctorAvailabilityRepository availabilityRepository;
    @Mock DoctorCalendar doctorCalendar;
    @Mock HubTeamService teamService;

    Center centre;
    CentreAppointment appointment;

    @BeforeEach
    void setUp() {
        centre = new Center();
        centre.setId(7L);
        centre.setName("Zaria Centre");
        centre.setCode("ZAR");

        appointment = new CentreAppointment();
        appointment.setId(11L);
        appointment.setCentre(centre);
        appointment.setReference("CAP-TEST");
    }

    @Nested
    @DisplayName("Wallet reversal")
    class Reversal {

        CentreWalletService wallet;
        Wallet centreWallet;
        WalletTransaction debit;

        @BeforeEach
        void setUp() {
            wallet = new CentreWalletService(walletRepository, ledgerRepository, alertRepository,
                    configuration, notifications, auditService);
            centreWallet = new Wallet();
            centreWallet.setId(3L);
            centreWallet.setCentre(centre);
            debit = new WalletTransaction();
            debit.setId(55L);
            debit.setWallet(centreWallet);
            debit.setAmount(new BigDecimal("5000.00"));
            debit.setDirection(LedgerDirection.DEBIT);
            when(alertRepository.findAllByClearedAtIsNullOrderByRaisedAtAsc()).thenReturn(List.of());
            when(ledgerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("01 a booking that was never charged returns nothing and posts nothing")
        void neverCharged() {
            assertThat(wallet.reverseBookingDebit(appointment, "cancelled")).isNull();
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("02 the charge comes back as a credit pointing at the debit")
        void reverses() {
            appointment.setWalletTransactionId(55L);
            when(ledgerRepository.findByTransactionReference("RV-CAP-TEST")).thenReturn(Optional.empty());
            when(ledgerRepository.findById(55L)).thenReturn(Optional.of(debit));
            when(ledgerRepository.deriveBalance(3L)).thenReturn(new BigDecimal("20000.00"));

            WalletTransaction credit = wallet.reverseBookingDebit(appointment, "Doctor did not join");

            assertThat(credit.getDirection()).isEqualTo(LedgerDirection.CREDIT);
            assertThat(credit.getStatus()).isEqualTo(LedgerEntryStatus.POSTED);
            assertThat(credit.getAmount()).isEqualByComparingTo("5000.00");
            assertThat(credit.getBalanceAfter()).isEqualByComparingTo("25000.00");
            assertThat(credit.getReversesTransactionId()).isEqualTo(55L);
            assertThat(credit.getTransactionReference()).isEqualTo("RV-CAP-TEST");
            assertThat(centreWallet.getBalance()).isEqualByComparingTo("25000.00");
            // The debit itself is left alone; marking it REVERSED would refund twice.
            assertThat(debit.getStatus()).isEqualTo(LedgerEntryStatus.POSTED);
        }

        @Test
        @DisplayName("03 a second reversal of the same booking returns the first and pays nothing")
        void idempotent() {
            appointment.setWalletTransactionId(55L);
            WalletTransaction earlier = new WalletTransaction();
            earlier.setId(77L);
            when(ledgerRepository.findByTransactionReference("RV-CAP-TEST")).thenReturn(Optional.of(earlier));

            assertThat(wallet.reverseBookingDebit(appointment, "again")).isSameAs(earlier);
            verify(ledgerRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Cancellation, approval timing and expiry")
    class Booking {

        CentreBookingService booking;
        CentreReferral referral;
        Slot slot;

        @BeforeEach
        void setUp() {
            booking = new CentreBookingService(patientRepository, referralRepository, appointmentRepository,
                    slotRepository, roomRepository, mockWallet, availabilityRepository, doctorCalendar,
                    teamService, notifications, auditService);
            referral = new CentreReferral();
            referral.setStatus(ReferralStatus.SCHEDULED);
            slot = new Slot();
            slot.setState(SlotState.BOOKED);
            appointment.setReferral(referral);
            appointment.setSlot(slot);
            appointment.setAppointmentDate(LocalDateTime.now().plusDays(1));
            appointment.setScheduledEndAt(LocalDateTime.now().plusDays(1).plusMinutes(30));
            when(appointmentRepository.findByPublicId("CA-1")).thenReturn(Optional.of(appointment));
        }

        @Test
        @DisplayName("04 cancelling a confirmed consultation refunds, frees the slot and reopens the referral")
        void cancelConfirmed() {
            appointment.setStatus(Status.APPROVED);
            WalletTransaction credit = new WalletTransaction();
            credit.setId(90L);
            when(mockWallet.reverseBookingDebit(eq(appointment), anyString())).thenReturn(credit);

            booking.cancelByHub("CA-1", "Doctor called away to an emergency");

            assertThat(appointment.getStatus()).isEqualTo(Status.CANCELLED);
            assertThat(appointment.getWalletReversalId()).isEqualTo(90L);
            assertThat(appointment.getCancellationReason()).isEqualTo("Doctor called away to an emergency");
            assertThat(slot.getState()).isEqualTo(SlotState.AVAILABLE);
            assertThat(referral.getStatus()).isEqualTo(ReferralStatus.SUBMITTED);
            verify(notifications).notifyRole(eq("CENTRE_HUB_COORDINATOR"), eq(centre),
                    eq(NotificationType.APPOINTMENT_CANCELLED), anyString(), anyString(), anyString(),
                    anyString(), any());
        }

        @Test
        @DisplayName("05 a consultation that has started cannot be cancelled")
        void cancelStarted() {
            appointment.setStatus(Status.APPROVED);
            appointment.setAppointmentDate(LocalDateTime.now().minusMinutes(5));

            assertThatThrownBy(() -> booking.cancelByHub("CA-1", "Too late now"))
                    .isInstanceOf(CentreBookingService.CentreBookingException.class)
                    .hasMessageContaining("already started");
            verify(mockWallet, never()).reverseBookingDebit(any(), anyString());
        }

        @Test
        @DisplayName("06 a time that has started cannot be approved, so the wallet is not charged")
        void approvePast() {
            appointment.setStatus(Status.AWAITING_APPROVAL);
            appointment.setAppointmentDate(LocalDateTime.now().minusMinutes(1));

            assertThatThrownBy(() -> booking.approve("CA-1", new Users(), null, null, null, "ROOM-1", null))
                    .isInstanceOf(CentreBookingService.CentreBookingException.class)
                    .hasMessageContaining("already started");
            verify(mockWallet, never()).debitForBooking(any(), any(), any());
        }

        @Test
        @DisplayName("07 a doctor already booked on either pathway is refused before any charge")
        void doctorClash() {
            appointment.setStatus(Status.AWAITING_APPROVAL);
            Users doctor = new Users();
            doctor.setId(5L);
            when(availabilityRepository.isAvailable(eq(5L), any(), any())).thenReturn(true);
            when(doctorCalendar.busy(eq(5L), any(), any(), isNull(), eq(11L))).thenReturn(true);

            assertThatThrownBy(() -> booking.approve("CA-1", doctor, null, null, null, "ROOM-1", null))
                    .isInstanceOf(CentreBookingService.CentreBookingException.class)
                    .hasMessageContaining("already has a consultation");
            verify(mockWallet, never()).debitForBooking(any(), any(), any());
        }

        @Test
        @DisplayName("08 an undecided request whose time has come expires and reopens its referral")
        void expire() {
            appointment.setStatus(Status.AWAITING_APPROVAL);
            when(appointmentRepository.findAwaitingApprovalStartedBy(any())).thenReturn(List.of(appointment));

            assertThat(booking.expireUndecided(LocalDateTime.now())).isEqualTo(1);
            assertThat(appointment.getStatus()).isEqualTo(Status.EXPIRED);
            assertThat(slot.getState()).isEqualTo(SlotState.AVAILABLE);
            assertThat(referral.getStatus()).isEqualTo(ReferralStatus.SUBMITTED);
            verify(mockWallet, never()).reverseBookingDebit(any(), anyString());
        }
    }
}
