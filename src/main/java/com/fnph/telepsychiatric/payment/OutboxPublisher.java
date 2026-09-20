package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
import com.fnph.telepsychiatric.scheduling.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Publishes what the outbox recorded.
 *
 * <h2>Why this exists at all</h2>
 *
 * Provider verification records the payment independently from booking. The
 * current patient journey then selects a slot and links the payment, intake and
 * appointment in one transaction. This publisher remains for older hold-first
 * clients and for deployments that still verify a payment against an existing
 * held appointment.
 *
 * A verification with no held appointment is expected: the successful unused
 * payment unlocks the calendar and is consumed when the patient submits a
 * selected time.
 *
 * <h2>Retries are safe</h2>
 *
 * {@code confirmPayment} returns unchanged if the appointment has already moved
 * on, so a redelivery after a crash mid-publish does nothing twice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private static final int BATCH = 50;
    private static final int MAX_ATTEMPTS = 5;

    private final OutboxRepository outboxRepository;
    private final AppointmentRepository appointmentRepository;
    private final PaymentRepository paymentRepository;
    private final BookingService bookingService;

    /** Run every few seconds. */
    @Transactional
    public int publishPending() {
        List<OutboxEvent> pending = outboxRepository.findUnpublished(
                LocalDateTime.now(), PageRequest.of(0, BATCH));

        int published = 0;
        for (OutboxEvent event : pending) {
            try {
                dispatch(event);
                event.setPublishedAt(LocalDateTime.now());
                outboxRepository.save(event);
                published++;
            } catch (Exception e) {
                event.setAttempts(event.getAttempts() + 1);
                event.setLastError(e.getMessage());
                // Back off rather than spinning on a broken consumer. A failing
                // event retried every second fills the log and hides the others.
                event.setNextAttemptAt(LocalDateTime.now()
                        .plusMinutes(Math.min(30, 1L << event.getAttempts())));
                outboxRepository.save(event);

                if (event.getAttempts() >= MAX_ATTEMPTS) {
                    // Left for a person. An event that has failed five times is
                    // not going to succeed on the sixth, and a paid patient who
                    // cannot book needs somebody to look.
                    log.error("Outbox event {} for {} {} has failed {} times and is now stuck: {}",
                            event.getEventType(), event.getAggregateType(),
                            event.getAggregateId(), event.getAttempts(), e.getMessage());
                } else {
                    log.warn("Outbox event {} failed, attempt {}: {}",
                            event.getPublicId(), event.getAttempts(), e.getMessage());
                }
            }
        }
        return published;
    }

    private void dispatch(OutboxEvent event) {
        if (!"PAYMENT_VERIFIED".equals(event.getEventType())) {
            // Unknown types are marked published rather than retried forever.
            // A type this build does not handle is a deployment mismatch, not a
            // transient fault.
            log.warn("Outbox event type {} is not handled here", event.getEventType());
            return;
        }

        Payment payment = paymentRepository.findById(event.getAggregateId())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment " + event.getAggregateId() + " no longer exists"));

        Appointment appointment = appointmentRepository
                .findAllByPatientIdOrderByAppointmentDateDesc(payment.getPatient().getId())
                .stream()
                .filter(a -> a.getStatus() == Status.SLOT_HELD)
                .findFirst()
                .orElse(null);

        if (appointment == null) {
            // Expected in the payment-first patient flow. The verified unused
            // payment unlocks slot selection and is linked during submission.
            log.info("Payment {} verified with no held appointment. The balance covers "
                    + "the patient's next booking.", payment.getReference());
            return;
        }

        Appointment confirmed = bookingService.confirmPayment(appointment, payment);

        // The payment side of the link. Nothing set it before, so every verified
        // payment looked unused forever: findUnusedVerifiedPayments returned the
        // patient's first payment on every later initiate, no new event was
        // raised, and every booking after the first sat held until it expired.
        if (confirmed.getStatus() == Status.AWAITING_APPROVAL && payment.getAppointment() == null) {
            payment.setAppointment(confirmed);
            paymentRepository.save(payment);
        }
    }

    /** Events stuck past the retry limit. Surfaced on the operations dashboard. */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public List<OutboxEvent> stuck() {
        return outboxRepository.findUnpublished(
                        LocalDateTime.now().plusYears(1), PageRequest.of(0, 200)).stream()
                .filter(e -> e.getAttempts() >= MAX_ATTEMPTS)
                .toList();
    }
}
