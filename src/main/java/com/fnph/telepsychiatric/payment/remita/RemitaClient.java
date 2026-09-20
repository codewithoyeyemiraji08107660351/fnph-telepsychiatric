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
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class RemitaClient {

    private static final String CHARGE_PATH =
            "/services/connect-gateway/api/v1/payment-engine/payment/charge";

    private static final String VERIFY_PATH =
            "/services/connect-gateway/api/v1/payment-engine/payment/merchant/verify/";

    private final RemitaProperties properties;
    private final ObjectMapper objectMapper;

    private WebClient client() {
        return WebClient.builder()
                .baseUrl(properties.getApiBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .defaultHeader("secretKey", properties.getSecretKey())
                .build();
    }

    /**
     * Creates a Remita Connect Gateway payment.
     *
     * The application stores monetary amounts in NGN as BigDecimal.
     * Remita Connect Gateway expects amount in minor units (kobo)
     * as a whole number.
     *
     * Example:
     *   10000.00 NGN -> 1000000 kobo
     */
    @CircuitBreaker(name = "remita", fallbackMethod = "initiateUnavailable")
    @Retry(name = "remita")
    public InitiationResult initiate(
            String paymentIdentifier,
            BigDecimal amount,
            String firstName,
            String lastName,
            String email,
            String phone
    ) {

        if (amount == null) {
            throw new IllegalArgumentException("Payment amount cannot be null");
        }

        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }

        /*
         * Convert NGN to kobo.
         *
         * ₦10,000.00 -> 1,000,000
         *
         * Rounding is deliberately HALF_UP after forcing two decimal
         * places because NGN is stored with scale 2.
         */
        long amountInKobo = amount
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact();

        Map<String, Object> body = new LinkedHashMap<>();

        body.put("firstName", safe(firstName));
        body.put("lastName", safe(lastName));
        body.put("email", safe(email));
        String remitaPhone = (phone == null || phone.isBlank())
                ? "08107660351"
                : phone.trim();

        body.put("phoneNumber", remitaPhone);

        /*
         * IMPORTANT:
         * Keep the FNPH reference as paymentIdentifier.
         * Do not replace it with a Remita-generated reference.
         */
        body.put("paymentIdentifier", paymentIdentifier);

        body.put("currency", "NGN");

        body.put(
                "narration",
                "FNPH Kaduna telepsychiatry consultation"
        );

        /*
         * Remita Connect Gateway requires a whole number
         * representing the amount in minor units (kobo).
         */
        body.put("amount", amountInKobo);

        log.info(
                "Creating Remita Connect Gateway payment: reference={}, amountNGN={}, amountKobo={}",
                paymentIdentifier,
                amount,
                remitaPhone,
                amountInKobo
        );

        String raw = client()
                .post()
                .uri(CHARGE_PATH)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .block();

        return parseInitiation(
                paymentIdentifier,
                raw
        );
    }

    /**
     * Connect Gateway Check Status endpoint.
     *
     * The current Connect Gateway test environment may not return
     * a useful documented verification body. Therefore TEST mode
     * should rely on the configured charge-success behavior.
     */
    @CircuitBreaker(name = "remita", fallbackMethod = "verifyUnavailable")
    @Retry(name = "remita")
    public VerificationResult verify(String transRef) {

        log.info(
                "Checking Remita payment status: transRef={}",
                transRef
        );

        String raw = client()
                .get()
                .uri(VERIFY_PATH + transRef)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .block();

        return parseVerification(
                transRef,
                raw
        );
    }

    private InitiationResult parseInitiation(
            String reference,
            String raw
    ) {

        try {

            JsonNode root = objectMapper.readTree(
                    raw == null ? "{}" : raw
            );

            String status = text(root, "status");
            String message = text(root, "message");

            String paymentLink = null;

            JsonNode data = root.get("data");

            if (data != null && !data.isNull()) {
                paymentLink = text(data, "paymentLink");
            }

            /*
             * Connect Gateway status 00 means the charge was accepted.
             *
             * Do NOT require paymentLink for the charge itself to be
             * considered successful. The payment link is useful for
             * hosted checkout, but it is not the same thing as the
             * charge status.
             */
            boolean successful = "00".equals(status);

            log.info(
                    "Remita charge response: reference={}, status={}, paymentLinkPresent={}",
                    reference,
                    status,
                    paymentLink != null && !paymentLink.isBlank()
            );

            return new InitiationResult(
                    successful,
                    paymentLink,
                    reference,
                    status,
                    message,
                    raw
            );

        } catch (Exception e) {

            log.error(
                    "Unable to parse Remita charge response for {}: {}",
                    reference,
                    e.getMessage()
            );

            return new InitiationResult(
                    false,
                    null,
                    reference,
                    "PARSE_ERROR",
                    e.getMessage(),
                    raw
            );
        }
    }

    private VerificationResult parseVerification(
            String transRef,
            String raw
    ) {

        if (raw == null || raw.isBlank()) {

            return new VerificationResult(
                    true,
                    false,
                    true,
                    "EMPTY_RESPONSE",
                    "Remita returned no verification response.",
                    null,
                    transRef,
                    null,
                    raw
            );
        }

        try {

            JsonNode root = objectMapper.readTree(raw);

            String status = text(root, "status");
            String message = text(root, "message");

            boolean paid = "00".equals(status);

            return new VerificationResult(
                    true,
                    paid,
                    !paid,
                    status,
                    message,
                    null,
                    transRef,
                    null,
                    raw
            );

        } catch (Exception e) {

            log.error(
                    "Unable to parse Remita verification response for {}: {}",
                    transRef,
                    e.getMessage()
            );

            return new VerificationResult(
                    false,
                    false,
                    false,
                    "PARSE_ERROR",
                    e.getMessage(),
                    null,
                    transRef,
                    null,
                    raw
            );
        }
    }

    private String text(
            JsonNode node,
            String field
    ) {

        if (node == null) {
            return null;
        }

        JsonNode value = node.get(field);

        if (value == null || value.isNull()) {
            return null;
        }

        return value.asText();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private InitiationResult initiateUnavailable(
            String paymentIdentifier,
            BigDecimal amount,
            String firstName,
            String lastName,
            String email,
            String phone,
            Throwable throwable
    ) {

        log.error(
                "Remita charge unavailable for {}: {}",
                paymentIdentifier,
                throwable.getMessage()
        );

        return new InitiationResult(
                false,
                null,
                paymentIdentifier,
                "UNAVAILABLE",
                "Remita payment service is temporarily unavailable.",
                null
        );
    }

    private VerificationResult verifyUnavailable(
            String transRef,
            Throwable throwable
    ) {

        log.error(
                "Remita verification unavailable for {}: {}",
                transRef,
                throwable.getMessage()
        );

        return new VerificationResult(
                false,
                false,
                true,
                "UNAVAILABLE",
                throwable.getMessage(),
                null,
                transRef,
                null,
                null
        );
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