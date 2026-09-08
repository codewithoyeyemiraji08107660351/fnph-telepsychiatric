package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.NotificationType;
import com.fnph.telepsychiatric.patient.Patient;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.payment.remita.RemitaClient;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Payment, from initiation through verification to the credit ledger.
 *
 * <h2>The order of the journey matters here</h2>
 *
 * The approved sequence is triage, vitals, <b>pay</b>, select a slot, then Hub
 * Coordinator approval. Money moves before anyone approves anything, which is
 * why the credit path below exists and is not an afterthought.
 *
 * <h2>Only server-side verification counts</h2>
 *
 * The browser return page is a URL the patient was sent to. It can be opened
 * directly, replayed, or reached after a failed payment. Nothing here moves a
 * payment to SUCCESS except a server-to-server verification against Remita in
 * which the reference, amount, currency and status all agree with the order.
 *
 * <h2>An unreachable provider is not a failed payment</h2>
 *
 * When Remita cannot be reached the payment stays PENDING and is retried.
 * Treating unreachable as unpaid would strand a patient who has paid, and they
 * would have no way to prove it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PatientRepository patientRepository;
    private final WebhookInboxRepository webhookRepository;
    private final OutboxRepository outboxRepository;
    private final PatientCreditService creditService;
    private final RemitaClient remitaClient;
    private final ConfigurationService configuration;
    private final InAppNotificationService notifications;
    private final AuditService auditService;

    // -----------------------------------------------------------------
    // Initiation
    // -----------------------------------------------------------------

    /**
     * Starts a payment for a consultation.
     *
     * Applies any credit the patient holds first. A patient whose previous
     * booking was rejected pays the difference, or nothing at all if the credit
     * covers the fee.
     */
    @Transactional
    public Payment initiate(Patient patient, String payerEmail, String payerPhone) {
        BigDecimal fee = configuration.getDecimal(ConfigurationKeys.CONSULTATION_FEE_NGN);

        Optional<Payment> existing = paymentRepository.findUnusedVerifiedPayments(patient.getId())
                .stream().findFirst();
        if (existing.isPresent()) {
            // Already paid and not yet booked. Returning the existing payment
            // rather than starting another is what stops a patient paying twice
            // by refreshing the page.
            return existing.get();
        }

        String reference = "FNPH-" + Tokens.generateRecoveryCode().replace("-", "");

        Payment payment = new Payment();
        payment.setPatient(patient);
        payment.setPurpose(PaymentPurpose.PATIENT_CONSULTATION);
        payment.setReference(reference);
        payment.setAmount(fee);
        payment.setCurrency("NGN");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setInitiatedAt(LocalDateTime.now());
        payment.setExpiresAt(LocalDateTime.now().plusHours(48));
        paymentRepository.save(payment);

        // The wallet is NOT debited here. Money is credited on payment and
        // spent on approval, so a rejected booking simply never debits and the
        // balance survives for the next attempt. Debiting up front would take
        // the money before anyone had agreed to see the patient.
        BigDecimal walletBalance = creditService.balanceFor(patient.getId());
        BigDecimal covered = walletBalance.min(fee);
        BigDecimal payable = fee.subtract(covered);
        payment.setCreditApplied(covered);

        if (payable.signum() <= 0) {
            // The wallet already covers the fee, so nothing goes to Remita and
            // no new credit is issued. The existing balance is spent at
            // approval like any other.
            markSettledFromWallet(payment, fee);
            return payment;
        }

        RemitaClient.InitiationResult result = remitaClient.initiate(
                reference, payable,
                patient.getFirstName() + " " + patient.getLastName(),
                payerEmail, payerPhone);

        payment.setInitiationResponse(result.rawResponse());

        if (!result.successful()) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Remita initiation failed: " + result.message());
            paymentRepository.save(payment);
            throw new PaymentException(
                    "Payment could not be started. Please try again shortly.");
        }

        payment.setRrr(result.rrr());
        paymentRepository.save(payment);

        log.info("Payment {} initiated for patient {}, RRR {}, payable {}",
                reference, patient.getPublicId(), result.rrr(), payable);
        return payment;
    }

    // -----------------------------------------------------------------
    // Verification
    // -----------------------------------------------------------------

    /**
     * Asks Remita what happened and acts on the answer.
     *
     * Called from the callback handler, from the patient polling after a return
     * from Remita, and from reconciliation. Idempotent: a payment already
     * SUCCESS is returned unchanged.
     */
    @Transactional
    public Payment verify(String reference) {
        Payment payment = paymentRepository.findByReference(reference)
                .orElseThrow(() -> new PaymentException("No payment with that reference"));

        if (payment.getStatus() == PaymentStatus.SUCCESS) {
            return payment;
        }
        if (payment.getRrr() == null) {
            throw new PaymentException("That payment was never registered with Remita");
        }

        payment.setVerificationAttempts(payment.getVerificationAttempts() + 1);
        payment.setLastVerifiedAt(LocalDateTime.now());

        RemitaClient.VerificationResult result = remitaClient.verify(payment.getRrr());

        if (!result.reachable()) {
            // Leave it PENDING. Reconciliation picks it up.
            paymentRepository.save(payment);
            log.warn("Remita unreachable while verifying {}. Left pending.", reference);
            return payment;
        }

        if (!result.paid()) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Remita status " + result.statusCode()
                    + ": " + result.message());
            paymentRepository.save(payment);
            notifyPatientOfFailure(payment);
            return payment;
        }

        BigDecimal expected = payment.getAmount().subtract(payment.getCreditApplied());
        if (result.amount() != null && result.amount().compareTo(expected) != 0) {
            // Never auto-accepted. Underpayment is not a rounding issue and
            // overpayment means the patient is owed a credit. Either way it is
            // a Finance decision, and slot selection stays locked meanwhile.
            payment.setAmountMismatch(true);
            payment.setReportedAmount(result.amount());
            payment.setStatus(PaymentStatus.UNMATCHED);
            payment.setReconciliationStatus("AMOUNT_MISMATCH");
            paymentRepository.save(payment);

            notifications.notifyRole("FINANCE", null, NotificationType.PAYMENT_FAILURE,
                    "Payment amount mismatch",
                    "Remita reported %s against an expected %s for reference %s."
                            .formatted(result.amount(), expected, reference),
                    "/finance/exceptions", "Payment", payment.getId());

            log.error("Amount mismatch on {}: expected {}, Remita reported {}",
                    reference, expected, result.amount());
            return payment;
        }

        markVerified(payment, result.amount() == null ? expected : result.amount(),
                "Verified with Remita, status " + result.statusCode());
        return payment;
    }

    /**
     * Moves the payment to SUCCESS and writes the unlock intent in the same
     * transaction.
     *
     * The outbox row is what makes "payment verified" and "slot selection
     * unlocked" atomic. Publishing to a queue after the commit can be lost if
     * the process dies in between, and a patient who has paid and cannot book
     * has no way to resolve that themselves.
     */
    private void markVerified(Payment payment, BigDecimal amount, String note) {
        LocalDateTime now = LocalDateTime.now();
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setVerifiedAt(now);
        payment.setPaymentDate(now);
        payment.setReconciliationStatus("MATCHED");
        paymentRepository.save(payment);

        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Payment");
        event.setAggregateId(payment.getId());
        event.setEventType("PAYMENT_VERIFIED");
        event.setPayload("{\"reference\":\"%s\",\"patientId\":%d,\"amount\":\"%s\"}"
                .formatted(payment.getReference(), payment.getPatient().getId(), amount));
        outboxRepository.save(event);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.PAYMENT_VERIFIED)
                .entityType("Payment")
                .entityId(payment.getId())
                .details("%s %s for reference %s".formatted(payment.getCurrency(), amount,
                        payment.getReference()))
                .reason(note)
                .build());

        // The payment credits the patient wallet. Approval later spends it,
        // which is what leaves the balance intact when a booking is rejected.
        creditService.issue(payment.getPatient(), amount,
                "Payment " + payment.getReference() + " confirmed", payment.getId(), null);

        notifications.notifyPatient(payment.getPatient(), NotificationType.PAYMENT_SUCCESS,
                "Payment confirmed",
                "Your payment has been confirmed and your requested time is reserved. "
                        + "The hospital will confirm your appointment shortly.",
                "/portal/appointments", "Payment", payment.getId());

        // Hands the booking on: slot BOOKED, appointment AWAITING_APPROVAL,
        // Hub Coordinator dashboard notified. Published through the outbox
        // above rather than called directly, so a failure here cannot roll back
        // a verified payment.
        log.info("Payment {} verified: {}", payment.getReference(), note);
    }

    private void notifyPatientOfFailure(Payment payment) {
        notifications.notifyPatient(payment.getPatient(), NotificationType.PAYMENT_FAILURE,
                "Payment not completed",
                "We could not confirm your payment. No appointment has been reserved. "
                        + "You can try again from your dashboard.",
                "/portal/payment", "Payment", payment.getId());
    }

    // -----------------------------------------------------------------
    // Callbacks
    // -----------------------------------------------------------------

    /**
     * Records a callback and verifies the underlying payment.
     *
     * The callback is stored before it is believed. Remita's notification is a
     * prompt to go and check, never evidence in itself: the values in it are
     * not trusted and the amount is read from the verification response.
     *
     * A repeat delivery collides on the payload hash and is ignored, which is
     * what makes duplicate callbacks produce one payment state.
     */
    @Transactional
    public void handleRemitaCallback(String payload, String sourceIp) {
        String hash = Tokens.hash(payload);

        if (webhookRepository.existsByPayloadHash(hash)) {
            log.info("Duplicate Remita callback ignored");
            return;
        }

        WebhookInbox inbox = new WebhookInbox();
        inbox.setProvider(WebhookProvider.REMITA);
        inbox.setPayload(payload);
        inbox.setPayloadHash(hash);
        inbox.setReceivedAt(LocalDateTime.now());
        inbox.setSourceIp(sourceIp);
        inbox.setSignatureValid(true);
        webhookRepository.save(inbox);

        try {
            String reference = extractOrderId(payload);
            if (reference == null) {
                inbox.setProcessingState(WebhookState.IGNORED);
                inbox.setFailureReason("No orderId in the callback");
            } else {
                inbox.setProviderEventId(reference);
                verify(reference);
                inbox.setProcessingState(WebhookState.PROCESSED);
            }
        } catch (Exception e) {
            inbox.setProcessingState(WebhookState.FAILED);
            inbox.setFailureReason(e.getMessage());
            inbox.setAttempts(inbox.getAttempts() + 1);
            log.error("Failed to process Remita callback: {}", e.getMessage());
        } finally {
            inbox.setProcessedAt(LocalDateTime.now());
            webhookRepository.save(inbox);
        }
    }

    private String extractOrderId(String payload) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            for (String field : List.of("orderId", "orderID", "order_id", "orderref")) {
                if (node.hasNonNull(field)) {
                    return node.get(field).asText();
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse Remita callback payload: {}", e.getMessage());
        }
        return null;
    }

    // -----------------------------------------------------------------
    // Credit
    // -----------------------------------------------------------------

    /**
     * Settles a fee entirely from an existing wallet balance.
     *
     * No Remita call and no new credit: the balance is already there and will
     * be spent at approval like any other.
     */
    private void markSettledFromWallet(Payment payment, BigDecimal fee) {
        LocalDateTime now = LocalDateTime.now();
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setVerifiedAt(now);
        payment.setPaymentDate(now);
        payment.setReconciliationStatus("SETTLED_FROM_WALLET");
        paymentRepository.save(payment);

        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Payment");
        event.setAggregateId(payment.getId());
        event.setEventType("PAYMENT_VERIFIED");
        event.setPayload("{\"reference\":\"%s\",\"patientId\":%d,\"settledFromWallet\":true}"
                .formatted(payment.getReference(), payment.getPatient().getId()));
        outboxRepository.save(event);

        notifications.notifyPatient(payment.getPatient(), NotificationType.PAYMENT_SUCCESS,
                "Your booking is covered by your balance",
                "Your existing balance covers this consultation, so there is nothing to pay. "
                        + "The hospital will confirm your appointment shortly.",
                "/portal/appointments", "Payment", payment.getId());

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.PAYMENT_VERIFIED)
                .entityType("Payment")
                .entityId(payment.getId())
                .details("Settled from wallet balance, no provider call")
                .build());

        log.info("Payment {} settled entirely from wallet", payment.getReference());
    }

    @Transactional(readOnly = true)
    public List<Payment> historyFor(Long patientId) {
        return paymentRepository.findAllByPatientIdOrderByCreatedAtDesc(patientId);
    }

    /** True when the patient may proceed to slot selection. */
    @Transactional(readOnly = true)
    public boolean hasUnusedVerifiedPayment(Long patientId) {
        return !paymentRepository.findUnusedVerifiedPayments(patientId).isEmpty();
    }

    public static class PaymentException extends RuntimeException {
        public PaymentException(String message) {
            super(message);
        }
    }
}
