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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

/**
 * Remita REST client.
 *
 * <h2>Two calls matter</h2>
 *
 * <b>Initiation</b> registers the order and returns an RRR, the reference the
 * patient quotes at any payment channel.
 *
 * <b>Verification</b> is server-to-server and is the only thing that moves a
 * payment to SUCCESS. A browser landing on a success page proves nothing: the
 * URL can be opened directly, replayed, or reached after a failed payment.
 *
 * <h2>Protected against Remita being slow or down</h2>
 *
 * A circuit breaker and a retry, because an unprotected outbound call to a
 * third party will eventually exhaust the request threads and take the whole
 * clinical portal down with it. A patient who cannot pay for ten minutes is a
 * much smaller problem than a doctor who cannot open a consultation.
 *
 * <h2>Endpoint shapes</h2>
 *
 * Paths and hash construction follow Remita's published eChannel API. Confirm
 * them against the credentials pack FNPH supplies before the pilot: Remita
 * varies these between merchant configurations, and the hash is unforgiving
 * about field order.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RemitaClient {

    private final RemitaProperties properties;
    private final ObjectMapper objectMapper;

    private WebClient client() {
        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", authorizationHeader())
                .build();
    }

    private String authorizationHeader() {
        return "remitaConsumerKey=%s,remitaConsumerToken=%s"
                .formatted(properties.getMerchantId(), properties.getApiToken());
    }

    /**
     * Registers an order and returns the RRR.
     *
     * @param orderId internal reference, echoed back by Remita
     * @param amount  the fee, in naira
     */
    @CircuitBreaker(name = "remita", fallbackMethod = "initiateUnavailable")
    @Retry(name = "remita")
    public InitiationResult initiate(String orderId, BigDecimal amount,
                                     String payerName, String payerEmail, String payerPhone) {

        String hash = sha512(properties.getMerchantId() + properties.getServiceTypeId()
                + orderId + amount.toPlainString() + properties.getApiKey());

        Map<String, Object> body = Map.of(
                "serviceTypeId", properties.getServiceTypeId(),
                "amount", amount.toPlainString(),
                "orderId", orderId,
                "payerName", payerName,
                "payerEmail", payerEmail,
                "payerPhone", payerPhone == null ? "" : payerPhone,
                "description", "FNPH Kaduna telepsychiatry consultation",
                "responseurl", properties.getResponseUrl());

        String raw = client().post()
                .uri("/echannelsvc/merchant/api/paymentinit")
                .header("Authorization", "remitaConsumerKey=%s,remitaConsumerToken=%s"
                        .formatted(properties.getMerchantId(), hash))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .block();

        return parseInitiation(raw);
    }

    /**
     * Asks Remita what actually happened.
     *
     * This is the authority. Anything the browser reported is a hint that a
     * verification should run, not a result.
     */
    @CircuitBreaker(name = "remita", fallbackMethod = "verifyUnavailable")
    @Retry(name = "remita")
    public VerificationResult verify(String rrr) {
        String hash = sha512(rrr + properties.getApiKey() + properties.getMerchantId());

        String raw = client().get()
                .uri("/echannelsvc/{merchantId}/{rrr}/{hash}/status.reg.json",
                        properties.getMerchantId(), rrr, hash)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .block();

        return parseVerification(raw);
    }

    // -----------------------------------------------------------------

    private InitiationResult parseInitiation(String raw) {
        try {
            String json = stripJsonp(raw);
            JsonNode node = objectMapper.readTree(json);
            String status = text(node, "statuscode");
            String rrr = text(node, "RRR");

            // "025" is Remita's success code for initiation. Treated as the
            // only success rather than assuming anything non-error worked.
            boolean ok = "025".equals(status) && rrr != null && !rrr.isBlank();
            return new InitiationResult(ok, rrr, status, text(node, "status"), raw);
        } catch (Exception e) {
            log.error("Could not parse Remita initiation response: {}", e.getMessage());
            return new InitiationResult(false, null, "PARSE_ERROR", e.getMessage(), raw);
        }
    }

    private VerificationResult parseVerification(String raw) {
        try {
            String json = stripJsonp(raw);
            JsonNode node = objectMapper.readTree(json);

            String status = text(node, "status");
            String message = text(node, "message");
            String amountText = text(node, "amount");
            BigDecimal amount = amountText == null ? null : new BigDecimal(amountText);

            // "00" and "01" are Remita's successful-payment codes. Every other
            // value is treated as not paid. Failing closed here is the whole
            // point: guessing that an unknown code means success would unlock
            // booking for someone who has not paid.
            boolean paid = "00".equals(status) || "01".equals(status);

            return new VerificationResult(true, paid, status, message, amount,
                    text(node, "orderId"), text(node, "transactiontime"),
                    text(node, "paymentDate"), raw);
        } catch (Exception e) {
            log.error("Could not parse Remita verification response: {}", e.getMessage());
            return new VerificationResult(false, false, "PARSE_ERROR", e.getMessage(),
                    null, null, null, null, raw);
        }
    }

    /** Remita sometimes wraps JSON in a JSONP callback. */
    private String stripJsonp(String raw) {
        if (raw == null) {
            return "{}";
        }
        String trimmed = raw.trim();
        int open = trimmed.indexOf('{');
        int close = trimmed.lastIndexOf('}');
        return (open >= 0 && close > open) ? trimmed.substring(open, close + 1) : trimmed;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String sha512(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(128);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    // Fallbacks. Never claim success when the provider is unreachable.

    @SuppressWarnings("unused")
    private InitiationResult initiateUnavailable(String orderId, BigDecimal amount,
                                                 String payerName, String payerEmail,
                                                 String payerPhone, Throwable t) {
        log.error("Remita initiation unavailable for order {}: {}", orderId, t.getMessage());
        return new InitiationResult(false, null, "UNAVAILABLE",
                "Payment could not be started. Try again shortly.", null);
    }

    @SuppressWarnings("unused")
    private VerificationResult verifyUnavailable(String rrr, Throwable t) {
        log.error("Remita verification unavailable for {}: {}", rrr, t.getMessage());
        // reachable=false, so the caller leaves the payment PENDING and retries.
        // Treating unreachable as unpaid would strand a patient who has paid.
        return new VerificationResult(false, false, "UNAVAILABLE", t.getMessage(),
                null, null, null, null, null);
    }

    public record InitiationResult(boolean successful, String rrr, String statusCode,
                                   String message, String rawResponse) {
    }

    public record VerificationResult(boolean reachable, boolean paid, String statusCode,
                                     String message, BigDecimal amount, String orderId,
                                     String transactionTime, String paymentDate,
                                     String rawResponse) {
    }
}
