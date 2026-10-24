package com.fnph.telepsychiatric.payment.remita;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Remita Connect Gateway: start a charge, check a payment.
 *
 * <h2>How a check decides "paid"</h2>
 *
 * The top-level {@code status} says whether Remita answered the request, not
 * whether the patient paid: the charge call returns "00" for a payment that
 * has only been created. So a payment is paid only when the transaction inside
 * {@code data} carries a success state. A reply this class cannot read keeps
 * the payment PENDING and is logged at error with the body, so the first live
 * payment shows the exact shape if it differs. Nothing unreadable is ever
 * counted as paid, and nothing is marked failed on a guess.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RemitaClient {

    private static final String CHARGE_PATH =
            "/services/connect-gateway/api/v1/payment-engine/payment/charge";

    private static final String VERIFY_PATH =
            "/services/connect-gateway/api/v1/payment-engine/payment/merchant/verify/";

    /** Request accepted. Says nothing about the money on its own. */
    static final String OK = "00";

    /** Fields that may carry the transaction state inside {@code data}, in order. */
    private static final List<String> STATE_FIELDS =
            List.of("paymentState", "paymentStatus", "transactionStatus", "status");

    private static final Set<String> PAID_STATES =
            Set.of("SUCCESSFUL", "SUCCESS", "PAID", "COMPLETED", "APPROVED", "00");

    private static final Set<String> FAILED_STATES =
            Set.of("FAILED", "FAILURE", "DECLINED", "CANCELLED", "CANCELED", "EXPIRED", "REVERSED", "ABANDONED");

    private static final List<String> AMOUNT_FIELDS =
            List.of("amount", "amountPaid", "transactionAmount");

    private static final List<String> REFERENCE_FIELDS =
            List.of("paymentIdentifier", "transRef", "reference");

    private static final int MAX_LOGGED_BODY = 2000;

    private final RemitaProperties properties;
    private final ObjectMapper objectMapper;

    private WebClient client() {
        return WebClient.builder()
                .baseUrl(properties.getApiBaseUrl().trim())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .defaultHeader("secretKey", properties.getSecretKey())
                .build();
    }

    private boolean configured() {
        return properties.hasSecretKey()
                && properties.getApiBaseUrl() != null
                && !properties.getApiBaseUrl().isBlank();
    }

    /**
     * Starts a Connect Gateway payment and returns the payment link.
     *
     * Not retried. A charge whose reply was lost may still exist at Remita,
     * and sending it again with the same reference can create a second one or
     * be refused. The patient retries from the payment screen instead, which
     * starts a fresh payment with a new reference.
     */
    @CircuitBreaker(name = "remita", fallbackMethod = "initiateUnavailable")
    public InitiationResult initiate(
            String paymentIdentifier,
            BigDecimal amount,
            String firstName,
            String lastName,
            String email,
            String phone
    ) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
        if (!configured()) {
            log.error("Remita is not configured (REMITA_API_BASE_URL or REMITA_SECRET_KEY missing). "
                    + "Payment {} not started.", paymentIdentifier);
            return new InitiationResult(false, null, paymentIdentifier, "NOT_CONFIGURED",
                    "Online payment is not available yet.", null);
        }

        // NGN is stored with scale 2; Connect Gateway takes whole kobo.
        long amountInKobo = toKobo(amount);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", safe(firstName));
        body.put("lastName", safe(lastName));
        body.put("email", safe(email));
        body.put("phoneNumber", safe(phone).trim());
        // The FNPH reference is the paymentIdentifier. Never swap it for a
        // Remita value: verification and reconciliation look it up by this.
        body.put("paymentIdentifier", paymentIdentifier);
        body.put("currency", "NGN");
        body.put("narration", "FNPH Kaduna telepsychiatry consultation");
        body.put("amount", amountInKobo);

        log.info("Remita charge: reference={}, amountKobo={}", paymentIdentifier, amountInKobo);

        String raw = client()
                .post()
                .uri(CHARGE_PATH)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .block();

        return parseInitiation(paymentIdentifier, raw);
    }

    /** Asks Remita for the state of a payment. Safe to repeat, so retried. */
    @CircuitBreaker(name = "remita", fallbackMethod = "verifyUnavailable")
    @Retry(name = "remita")
    public VerificationResult verify(String transRef) {
        if (!configured()) {
            log.error("Remita is not configured; cannot check {}", transRef);
            return new VerificationResult(false, false, true, "NOT_CONFIGURED",
                    "Remita is not configured.", null, transRef, null, null);
        }

        log.info("Remita check: reference={}", transRef);

        String raw = client()
                .get()
                .uri(VERIFY_PATH + transRef)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .block();

        return parseVerification(transRef, raw);
    }

    static long toKobo(BigDecimal naira) {
        return naira.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    InitiationResult parseInitiation(String reference, String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw == null ? "{}" : raw);
            String status = text(root, "status");
            String message = text(root, "message");
            JsonNode data = root.get("data");
            String paymentLink = data == null || data.isNull() ? null : text(data, "paymentLink");

            // "00" here means the charge was created. The patient has not paid.
            // The caller decides what a created charge without a link means.
            boolean created = OK.equals(status);

            if (created) {
                log.info("Remita charge created: reference={}, linkPresent={}", reference,
                        paymentLink != null && !paymentLink.isBlank());
            } else {
                log.error("Remita charge refused: reference={}, status={}, message={}, body={}",
                        reference, status, message, clip(raw));
            }
            return new InitiationResult(created, paymentLink, reference, status, message, raw);
        } catch (Exception e) {
            log.error("Unreadable Remita charge reply for {}: {} body={}", reference, e.getMessage(), clip(raw));
            return new InitiationResult(false, null, reference, "PARSE_ERROR", e.getMessage(), raw);
        }
    }

    VerificationResult parseVerification(String transRef, String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("Remita check for {} came back empty. Payment stays pending.", transRef);
            return pending(transRef, "EMPTY_RESPONSE", "Remita returned no verification response.", raw);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            log.error("Unreadable Remita check reply for {}: {} body={}", transRef, e.getMessage(), clip(raw));
            return new VerificationResult(false, false, true, "PARSE_ERROR", e.getMessage(),
                    null, transRef, null, raw);
        }

        String status = text(root, "status");
        String message = text(root, "message");

        if (!OK.equals(status)) {
            // Remita answered but could not give a result (not found yet,
            // not started, or a code this class does not know). Not a failure.
            log.info("Remita check for {}: status={}, message={}", transRef, status, message);
            return pending(transRef, status == null ? "NO_STATUS" : status, message, raw);
        }

        JsonNode tx = transaction(root.get("data"));
        String state = tx == null ? null : firstText(tx, STATE_FIELDS);

        if (state == null) {
            log.error("Remita check for {} returned 00 with no transaction state this service "
                    + "can read. Payment stays PENDING. body={}", transRef, clip(raw));
            return pending(transRef, "UNRECOGNISED", "Remita reply has no transaction state.", raw);
        }

        String reported = firstText(tx, REFERENCE_FIELDS);
        if (reported != null && !reported.equalsIgnoreCase(transRef)) {
            log.error("Remita check for {} returned a different payment ({}). Not accepted.",
                    transRef, reported);
            return pending(transRef, "REFERENCE_MISMATCH",
                    "Remita returned a different payment reference.", raw);
        }

        String normalised = state.trim().toUpperCase(Locale.ROOT);
        BigDecimal amount = amountInNaira(tx);
        String paidAt = firstText(tx, List.of("paymentDate", "transactionDate", "paidAt"));

        if (PAID_STATES.contains(normalised)) {
            if (amount == null) {
                log.error("Remita reports {} paid but gives no amount. Payment stays PENDING. body={}",
                        transRef, clip(raw));
                return pending(transRef, "NO_AMOUNT", "Remita reported paid without an amount.", raw);
            }
            return new VerificationResult(true, true, false, normalised, message, amount,
                    transRef, paidAt, raw);
        }
        if (FAILED_STATES.contains(normalised)) {
            return new VerificationResult(true, false, false, normalised, message, amount,
                    transRef, paidAt, raw);
        }
        return pending(transRef, normalised, message, raw);
    }

    /** {@code data} may be the transaction itself or a one-item list. */
    private static JsonNode transaction(JsonNode data) {
        if (data == null || data.isNull()) {
            return null;
        }
        if (data.isArray()) {
            return data.isEmpty() ? null : data.get(0);
        }
        return data.isObject() ? data : null;
    }

    /**
     * Connect Gateway takes the charge in kobo, and the check is read the same
     * way. A value written with a decimal point is read as naira. Any mismatch
     * with the order goes to Finance, so a wrong reading can hold a payment
     * but never pass a short one.
     */
    private static BigDecimal amountInNaira(JsonNode tx) {
        for (String field : AMOUNT_FIELDS) {
            JsonNode value = tx.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            try {
                String text = value.asText().trim().replace(",", "");
                if (text.isEmpty()) {
                    continue;
                }
                BigDecimal number = new BigDecimal(text);
                boolean writtenAsNaira = text.contains(".");
                return writtenAsNaira
                        ? number.setScale(2, RoundingMode.HALF_UP)
                        : number.movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
            } catch (NumberFormatException ignored) {
                // try the next field
            }
        }
        return null;
    }

    private static VerificationResult pending(String transRef, String code, String message, String raw) {
        return new VerificationResult(true, false, true, code, message, null, transRef, null, raw);
    }

    private static String firstText(JsonNode node, List<String> fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        return value.asText();
    }

    private static String clip(String raw) {
        if (raw == null) {
            return "null";
        }
        return raw.length() <= MAX_LOGGED_BODY ? raw : raw.substring(0, MAX_LOGGED_BODY) + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private InitiationResult initiateUnavailable(
            String paymentIdentifier, BigDecimal amount, String firstName, String lastName,
            String email, String phone, Throwable throwable) {
        log.error("Remita charge unavailable for {}: {}", paymentIdentifier, throwable.getMessage());
        return new InitiationResult(false, null, paymentIdentifier, "UNAVAILABLE",
                "Remita payment service is temporarily unavailable.", null);
    }

    private VerificationResult verifyUnavailable(String transRef, Throwable throwable) {
        log.error("Remita check unavailable for {}: {}", transRef, throwable.getMessage());
        return new VerificationResult(false, false, true, "UNAVAILABLE", throwable.getMessage(),
                null, transRef, null, null);
    }

    public record InitiationResult(
            boolean successful,
            String paymentLink,
            String reference,
            String statusCode,
            String message,
            String rawResponse
    ) {
    }

    public record VerificationResult(
            boolean reachable,
            boolean paid,
            boolean pending,
            String statusCode,
            String message,
            BigDecimal amount,
            String reference,
            String paymentDate,
            String rawResponse
    ) {
    }
}
