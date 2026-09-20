package com.fnph.telepsychiatric.payment;

import com.fnph.telepsychiatric.appointment.AppointmentRepository;
import com.fnph.telepsychiatric.appointment.Status;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Handles the complete FNPH payment lifecycle.
 *
 * TEST MODE:
 *
 * Connect Gateway charge response:
 *
 * {
 *     "status": "00",
 *     "message": "Approved or Completed Successfully.",
 *     "data": {
 *         "paymentLink": "..."
 *     }
 * }
 *
 * When REMITA_ACCEPT_CHARGE_SUCCESS_AS_PAID=true, status 00 is accepted
 * as payment success immediately for local/testing purposes.
 *
 * PRODUCTION:
 *
 * REMITA_ACCEPT_CHARGE_SUCCESS_AS_PAID=false
 *
 * Production payment confirmation must come from the appropriate
 * server-side payment confirmation mechanism.
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
    private final AppointmentRepository appointmentRepository;

    /**
     * Remita requires an email.
     */
    @Value("${application.notification.email.from}")
    private String fallbackPayerEmail;

    /**
     * TEST ONLY.
     *
     * When true:
     *
     * Connect Gateway charge status 00 is treated as SUCCESS.
     *
     * Set to false before production deployment.
     */
    @Value("${application.payment.remita.accept-charge-success-as-paid:true}")
    private boolean acceptChargeSuccessAsPaid;

    // -------------------------------------------------------------------------
    // INITIATION
    // -------------------------------------------------------------------------

    @Transactional
    public Payment initiate(
            Patient patient,
            String payerEmail,
            String payerPhone
    ) {

        BigDecimal fee =
                configuration.getDecimal(
                        ConfigurationKeys.CONSULTATION_FEE_NGN
                );

        /*
         * Prevent duplicate payment.
         *
         * If the patient already has an unused verified payment,
         * reuse it rather than creating another payment.
         */
        Optional<Payment> existing =
                paymentRepository
                        .findUnusedVerifiedPayments(patient.getId())
                        .stream()
                        .findFirst();

        if (existing.isPresent()) {

            boolean holding =
                    appointmentRepository
                            .findAllByPatientIdOrderByAppointmentDateDesc(
                                    patient.getId()
                            )
                            .stream()
                            .anyMatch(
                                    a -> a.getStatus() == Status.SLOT_HELD
                            );

            if (holding) {

                publishVerified(
                        existing.get(),
                        "reused for a new hold"
                );
            }

            return existing.get();
        }

        /*
         * Generate the FNPH payment identifier.
         *
         * This is also sent to Remita Connect Gateway as:
         *
         * paymentIdentifier
         */
        String reference =
                "FNPH-" +
                        Tokens.generateRecoveryCode()
                                .replace("-", "");

        Payment payment =
                new Payment();

        payment.setPatient(patient);

        payment.setPurpose(
                PaymentPurpose.PATIENT_CONSULTATION
        );

        payment.setReference(reference);

        payment.setAmount(fee);

        payment.setCurrency("NGN");

        payment.setStatus(
                PaymentStatus.PENDING
        );

        payment.setInitiatedAt(
                LocalDateTime.now()
        );

        payment.setExpiresAt(
                LocalDateTime.now().plusHours(48)
        );

        payment =
                paymentRepository.save(payment);

        /*
         * Apply existing patient wallet credit first.
         */
        BigDecimal walletBalance =
                creditService.balanceFor(
                        patient.getId()
                );

        BigDecimal covered =
                walletBalance.min(fee);

        BigDecimal payable =
                fee.subtract(covered);

        payment.setCreditApplied(
                covered
        );

        payment =
                paymentRepository.save(payment);

        /*
         * Entire consultation fee is covered by existing wallet credit.
         *
         * No Remita call is necessary.
         */
        if (payable.signum() <= 0) {

            markSettledFromWallet(
                    payment,
                    fee
            );

            return payment;
        }

        /*
         * Remita requires an email.
         */
        String email =
                firstUsableEmail(
                        payerEmail,
                        patient.getEmail(),
                        fallbackPayerEmail
                );

        String phone =
                payerPhone != null
                        && !payerPhone.isBlank()
                        ? payerPhone.trim()
                        : patient.getPhoneNumber();

        /*
         * Connect Gateway charge request.
         *
         * paymentIdentifier = FNPH reference
         */
        RemitaClient.InitiationResult result =
                remitaClient.initiate(
                        reference,
                        payable,
                        patient.getFirstName(),
                        patient.getLastName(),
                        email,
                        phone
                );

        /*
         * Keep the raw provider response for audit/debugging.
         */
        payment.setInitiationResponse(
                result.rawResponse()
        );

        /*
         * Connect Gateway initiation failed.
         */
        if (!result.successful()) {

            payment.setStatus(
                    PaymentStatus.FAILED
            );

            payment.setFailureReason(
                    "Remita initiation failed: " +
                            safeMessage(result.message())
            );

            paymentRepository.save(payment);

            log.error(
                    "Remita Connect Gateway initiation failed for {}: {}",
                    reference,
                    result.message()
            );

            throw new PaymentException(
                    "Payment could not be started. Please try again shortly."
            );
        }

        /*
         * Save the Connect Gateway payment link.
         */
        payment.setPaymentLink(
                result.paymentLink()
        );

        /*
         * IMPORTANT:
         *
         * Do NOT replace the FNPH reference with the paymentLink token.
         *
         * The reference remains the value sent as paymentIdentifier.
         */
        if (result.reference() != null
                && !result.reference().isBlank()
                && !result.reference().equals(reference)) {

            log.warn(
                    "Remita returned a different reference {} for FNPH reference {}. "
                            + "Keeping FNPH reference as paymentIdentifier.",
                    result.reference(),
                    reference
            );
        }

        payment =
                paymentRepository.save(payment);

        log.info(
                "Payment {} initiated for patient {}, payable={}, paymentLink returned",
                reference,
                patient.getPublicId(),
                payable
        );

        /*
         * ================================================================
         * TEST MODE
         * ================================================================
         *
         * The Connect Gateway charge endpoint has already returned:
         *
         * status = 00
         *
         * Therefore, during testing, we immediately accept it as SUCCESS.
         *
         * This allows the patient to proceed directly to booking without
         * depending on the broken/unavailable demo payment-link page.
         */
        if (acceptChargeSuccessAsPaid
                && "00".equals(result.statusCode())) {

            log.warn(
                    "TEST MODE: accepting Remita Connect Gateway charge status "
                            + "00 as SUCCESS for payment {}",
                    payment.getReference()
            );

            markVerified(
                    payment,
                    payable,
                    "TEST MODE - Connect Gateway charge status 00"
            );

            return paymentRepository
                    .findByReference(payment.getReference())
                    .orElse(payment);
        }

        /*
         * Normal production behavior:
         *
         * The payment remains PENDING until server-side confirmation.
         */
        return payment;
    }

    // -------------------------------------------------------------------------
    // PAYMENT VERIFICATION
    // -------------------------------------------------------------------------

    @Transactional
    public Payment verifyForPatient(
            String reference,
            Long patientId
    ) {

        Payment payment =
                paymentRepository
                        .findByReference(reference)
                        .filter(
                                p -> p.getPatient()
                                        .getId()
                                        .equals(patientId)
                        )
                        .orElseThrow(
                                () -> new PaymentException(
                                        "No payment with that reference"
                                )
                        );

        return verify(
                payment.getReference()
        );
    }

    /**
     * Server-to-server Remita verification.
     */
    @Transactional
    public Payment verify(
            String reference
    ) {

        Payment payment =
                paymentRepository
                        .findByReference(reference)
                        .orElseThrow(
                                () -> new PaymentException(
                                        "No payment with that reference"
                                )
                        );

        /*
         * Idempotency.
         *
         * This is especially important in test mode because the payment
         * may already have been marked SUCCESS during initiate().
         */
        if (payment.getStatus() == PaymentStatus.SUCCESS) {

            return payment;
        }

        /*
         * Connect Gateway payment identifier is the FNPH reference.
         */
        if (payment.getReference() == null
                || payment.getReference().isBlank()) {

            payment.setStatus(
                    PaymentStatus.FAILED
            );

            payment.setFailureReason(
                    "This payment has no valid payment identifier."
            );

            return paymentRepository.save(payment);
        }

        payment.setVerificationAttempts(
                payment.getVerificationAttempts() + 1
        );

        payment.setLastVerifiedAt(
                LocalDateTime.now()
        );

        RemitaClient.VerificationResult result =
                remitaClient.verify(
                        payment.getReference()
                );

        /*
         * Remita unavailable.
         *
         * Do not mark payment as failed.
         */
        if (!result.reachable()) {

            paymentRepository.save(payment);

            log.warn(
                    "Remita unreachable while verifying {}. "
                            + "Payment remains PENDING.",
                    reference
            );

            return payment;
        }

        /*
         * Payment still pending.
         */
        if (!result.paid()
                && result.pending()) {

            paymentRepository.save(payment);

            return payment;
        }

        /*
         * Provider reports payment failure.
         */
        if (!result.paid()) {

            boolean alreadyFailed =
                    payment.getStatus()
                            == PaymentStatus.FAILED;

            payment.setStatus(
                    PaymentStatus.FAILED
            );

            payment.setFailureReason(
                    "Remita status " +
                            result.statusCode() +
                            ": " +
                            safeMessage(result.message())
            );

            paymentRepository.save(payment);

            if (!alreadyFailed) {

                notifyPatientOfFailure(
                        payment
                );
            }

            return payment;
        }

        /*
         * Expected amount:
         *
         * consultation fee - existing patient credit
         */
        BigDecimal expected =
                payment.getAmount()
                        .subtract(
                                payment.getCreditApplied()
                        );

        /*
         * Check amount if provider supplied one.
         */
        if (result.amount() != null
                && result.amount().compareTo(expected) != 0) {

            payment.setAmountMismatch(
                    true
            );

            payment.setReportedAmount(
                    result.amount()
            );

            payment.setStatus(
                    PaymentStatus.UNMATCHED
            );

            payment.setReconciliationStatus(
                    "AMOUNT_MISMATCH"
            );

            paymentRepository.save(payment);

            notifications.notifyRole(
                    "FINANCE",
                    null,
                    NotificationType.PAYMENT_FAILURE,
                    "Payment amount mismatch",
                    "Remita reported %s against an expected %s for reference %s."
                            .formatted(
                                    result.amount(),
                                    expected,
                                    reference
                            ),
                    "/finance/exceptions",
                    "Payment",
                    payment.getId()
            );

            log.error(
                    "Amount mismatch on {}: expected {}, Remita reported {}",
                    reference,
                    expected,
                    result.amount()
            );

            return payment;
        }

        /*
         * Payment verified.
         */
        markVerified(
                payment,
                result.amount() == null
                        ? expected
                        : result.amount(),
                "Verified with Remita, status " +
                        result.statusCode()
        );

        return payment;
    }

    // -------------------------------------------------------------------------
    // SUCCESS
    // -------------------------------------------------------------------------

    /**
     * Marks a payment as successfully confirmed.
     *
     * This method is intentionally idempotent.
     */
    private void markVerified(
            Payment payment,
            BigDecimal amount,
            String note
    ) {

        /*
         * Safety against duplicate verification/callback processing.
         */
        if (payment.getStatus() == PaymentStatus.SUCCESS) {

            log.info(
                    "Payment {} is already SUCCESS. Skipping duplicate confirmation.",
                    payment.getReference()
            );

            return;
        }

        LocalDateTime now =
                LocalDateTime.now();

        payment.setStatus(
                PaymentStatus.SUCCESS
        );

        payment.setVerifiedAt(
                now
        );

        payment.setPaymentDate(
                now
        );

        payment.setReconciliationStatus(
                "MATCHED"
        );

        /*
         * Save SUCCESS before notifications/outbox work.
         */
        paymentRepository.save(payment);

        /*
         * Transactional outbox.
         */
        OutboxEvent event =
                new OutboxEvent();

        event.setAggregateType(
                "Payment"
        );

        event.setAggregateId(
                payment.getId()
        );

        event.setEventType(
                "PAYMENT_VERIFIED"
        );

        event.setPayload(
                "{\"reference\":\"%s\",\"patientId\":%d,\"amount\":\"%s\"}"
                        .formatted(
                                payment.getReference(),
                                payment.getPatient().getId(),
                                amount
                        )
        );

        outboxRepository.save(
                event
        );

        /*
         * Audit.
         */
        auditService.record(
                AuditService.AuditEvent.builder()
                        .action(
                                AuditAction.PAYMENT_VERIFIED
                        )
                        .entityType(
                                "Payment"
                        )
                        .entityId(
                                payment.getId()
                        )
                        .details(
                                "%s %s for reference %s"
                                        .formatted(
                                                payment.getCurrency(),
                                                amount,
                                                payment.getReference()
                                        )
                        )
                        .reason(note)
                        .build()
        );

        /*
         * Credit the patient's account.
         *
         * Existing business behavior preserved.
         */
        creditService.issue(
                payment.getPatient(),
                amount,
                "Payment " +
                        payment.getReference() +
                        " confirmed",
                payment.getId(),
                null
        );

        /*
         * Patient notification.
         */
        notifications.notifyPatient(
                payment.getPatient(),
                NotificationType.PAYMENT_SUCCESS,
                "Payment confirmed",
                "Your payment has been confirmed. You can now choose a date and time "
                        + "for your consultation.",
                "/portal/booking",
                "Payment",
                payment.getId()
        );

        /*
         * HIM notification.
         *
         * This is triggered for both:
         *
         * 1. Real server-side verification.
         * 2. TEST MODE status 00 acceptance.
         */
        notifications.notifyRole(
                "HIM",
                null,
                NotificationType.PAYMENT_VERIFIED_HIM,
                "Patient payment confirmed",
                "Payment %s of %s %s has been confirmed for patient %s. "
                        + "The patient can now proceed with consultation booking."
                        .formatted(
                                payment.getReference(),
                                payment.getCurrency(),
                                amount,
                                payment.getPatient().getPublicId()
                        ),
                "/him/payments",
                "Payment",
                payment.getId()
        );

        log.info(
                "Payment {} verified successfully. Reason: {}",
                payment.getReference(),
                note
        );
    }

    // -------------------------------------------------------------------------
    // REUSE VERIFIED PAYMENT
    // -------------------------------------------------------------------------

    private void publishVerified(
            Payment payment,
            String note
    ) {

        OutboxEvent event =
                new OutboxEvent();

        event.setAggregateType(
                "Payment"
        );

        event.setAggregateId(
                payment.getId()
        );

        event.setEventType(
                "PAYMENT_VERIFIED"
        );

        event.setPayload(
                "{\"reference\":\"%s\",\"patientId\":%d,\"note\":\"%s\"}"
                        .formatted(
                                payment.getReference(),
                                payment.getPatient().getId(),
                                note
                        )
        );

        outboxRepository.save(
                event
        );
    }

    // -------------------------------------------------------------------------
    // FAILURE
    // -------------------------------------------------------------------------

    private void notifyPatientOfFailure(
            Payment payment
    ) {

        notifications.notifyPatient(
                payment.getPatient(),
                NotificationType.PAYMENT_FAILURE,
                "Payment not completed",
                "We could not confirm your payment. No appointment has been reserved. "
                        + "You can try again from your dashboard.",
                "/portal/payment",
                "Payment",
                payment.getId()
        );
    }

    // -------------------------------------------------------------------------
    // CALLBACK
    // -------------------------------------------------------------------------

    /**
     * Remita callback is treated only as a trigger.
     *
     * The callback itself is not trusted as payment evidence.
     */
    @Transactional
    public void handleRemitaCallback(
            String payload,
            String sourceIp
    ) {

        String hash =
                Tokens.hash(payload);

        /*
         * Idempotent callback handling.
         */
        if (webhookRepository.existsByPayloadHash(hash)) {

            log.info(
                    "Duplicate Remita callback ignored"
            );

            return;
        }

        WebhookInbox inbox =
                new WebhookInbox();

        inbox.setProvider(
                WebhookProvider.REMITA
        );

        inbox.setPayload(
                payload
        );

        inbox.setPayloadHash(
                hash
        );

        inbox.setReceivedAt(
                LocalDateTime.now()
        );

        inbox.setSourceIp(
                sourceIp
        );

        /*
         * Callback endpoint is protected by the FNPH webhook mechanism.
         */
        inbox.setSignatureValid(
                true
        );

        webhookRepository.save(
                inbox
        );

        try {

            String reference =
                    extractPaymentIdentifier(
                            payload
                    );

            if (reference == null) {

                inbox.setProcessingState(
                        WebhookState.IGNORED
                );

                inbox.setFailureReason(
                        "No payment identifier in callback"
                );

            } else {

                inbox.setProviderEventId(
                        reference
                );

                /*
                 * Actual payment truth comes from verification.
                 *
                 * In TEST MODE, if the payment was already accepted
                 * during initiate(), verify() simply returns SUCCESS.
                 */
                verify(
                        reference
                );

                inbox.setProcessingState(
                        WebhookState.PROCESSED
                );
            }

        } catch (Exception e) {

            inbox.setProcessingState(
                    WebhookState.FAILED
            );

            inbox.setFailureReason(
                    e.getMessage()
            );

            inbox.setAttempts(
                    inbox.getAttempts() + 1
            );

            log.error(
                    "Failed to process Remita callback: {}",
                    e.getMessage(),
                    e
            );

        } finally {

            inbox.setProcessedAt(
                    LocalDateTime.now()
            );

            webhookRepository.save(
                    inbox
            );
        }
    }

    /**
     * Supports Connect Gateway and legacy callback names.
     */
    private String extractPaymentIdentifier(
            String payload
    ) {

        try {

            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(payload);

            for (String field : List.of(
                    "paymentIdentifier",
                    "paymentidentifier",
                    "transRef",
                    "transref",
                    "reference",
                    "orderId",
                    "orderID",
                    "order_id",
                    "orderref"
            )) {

                if (node.hasNonNull(field)) {

                    String value =
                            node.get(field).asText();

                    if (value != null
                            && !value.isBlank()) {

                        return value;
                    }
                }
            }

        } catch (Exception e) {

            log.warn(
                    "Could not parse Remita callback payload: {}",
                    e.getMessage()
            );
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // WALLET SETTLEMENT
    // -------------------------------------------------------------------------

    private void markSettledFromWallet(
            Payment payment,
            BigDecimal fee
    ) {

        /*
         * Safety against duplicate wallet settlement.
         */
        if (payment.getStatus() == PaymentStatus.SUCCESS) {

            return;
        }

        LocalDateTime now =
                LocalDateTime.now();

        payment.setStatus(
                PaymentStatus.SUCCESS
        );

        payment.setVerifiedAt(
                now
        );

        payment.setPaymentDate(
                now
        );

        payment.setReconciliationStatus(
                "SETTLED_FROM_WALLET"
        );

        paymentRepository.save(
                payment
        );

        OutboxEvent event =
                new OutboxEvent();

        event.setAggregateType(
                "Payment"
        );

        event.setAggregateId(
                payment.getId()
        );

        event.setEventType(
                "PAYMENT_VERIFIED"
        );

        event.setPayload(
                "{\"reference\":\"%s\",\"patientId\":%d,\"settledFromWallet\":true}"
                        .formatted(
                                payment.getReference(),
                                payment.getPatient().getId()
                        )
        );

        outboxRepository.save(
                event
        );

        notifications.notifyPatient(
                payment.getPatient(),
                NotificationType.PAYMENT_SUCCESS,
                "Your consultation is covered by your balance",
                "Your existing balance covers this consultation, so there is nothing to pay. "
                        + "You can now choose a date and time.",
                "/portal/booking",
                "Payment",
                payment.getId()
        );

        /*
         * HIM notification.
         */
        notifications.notifyRole(
                "HIM",
                null,
                NotificationType.PAYMENT_VERIFIED_HIM,
                "Consultation payment covered by patient credit",
                "Payment %s for patient %s has been settled from the patient's "
                        + "existing FNPH credit balance."
                        .formatted(
                                payment.getReference(),
                                payment.getPatient().getPublicId()
                        ),
                "/him/payments",
                "Payment",
                payment.getId()
        );

        auditService.record(
                AuditService.AuditEvent.builder()
                        .action(
                                AuditAction.PAYMENT_VERIFIED
                        )
                        .entityType(
                                "Payment"
                        )
                        .entityId(
                                payment.getId()
                        )
                        .details(
                                "Settled from wallet balance, no provider call"
                        )
                        .build()
        );

        log.info(
                "Payment {} settled entirely from wallet",
                payment.getReference()
        );
    }

    // -------------------------------------------------------------------------
    // HISTORY
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Payment> historyFor(
            Long patientId
    ) {

        return paymentRepository
                .findAllByPatientIdOrderByCreatedAtDesc(
                        patientId
                );
    }

    @Transactional(readOnly = true)
    public boolean hasUnusedVerifiedPayment(
            Long patientId
    ) {

        return !paymentRepository
                .findUnusedVerifiedPayments(patientId)
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private static String safeMessage(
            String message
    ) {

        return message == null
                || message.isBlank()
                ? "Unknown Remita error"
                : message;
    }

    public static class PaymentException
            extends RuntimeException {

        public PaymentException(
                String message
        ) {
            super(message);
        }
    }

    private static String firstUsableEmail(
            String... candidates
    ) {

        for (String candidate : candidates) {

            if (candidate != null
                    && candidate.contains("@")
                    && !candidate
                    .trim()
                    .toLowerCase()
                    .endsWith(".local")) {

                return candidate.trim();
            }
        }

        return "";
    }
}