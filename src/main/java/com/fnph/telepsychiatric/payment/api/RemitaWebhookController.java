package com.fnph.telepsychiatric.payment.api;

import com.fnph.telepsychiatric.payment.PaymentService;
import com.fnph.telepsychiatric.payment.remita.RemitaProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Webhooks")
public class RemitaWebhookController {

    private final PaymentService paymentService;
    private final RemitaProperties remitaProperties;

    @PostMapping("/remita/{secret}")
    @SecurityRequirements
    @Operation(
            summary = "Remita payment notification",
            description = """
                    Machine only. Never called by a client.

                    **Always returns 200**, even for a duplicate, an unparseable payload or
                    a payment that fails verification. Remita retries anything else, and a
                    retry storm against a callback that is working correctly is worse than
                    the original problem. What happened is recorded in the webhook inbox
                    and the payment record, not in the HTTP status.

                    **The callback is a prompt, not evidence.** Nothing in it is trusted.
                    Receiving one triggers a server-to-server verification against Remita,
                    and only that verification can move a payment to SUCCESS. A forged
                    callback therefore achieves nothing beyond causing a verification of a
                    payment that is not paid.

                    **Duplicates are a no-op.** Every callback is hashed and stored under a
                    unique index, so a repeat delivery collides and is ignored rather than
                    unlocking slot selection twice. This is what satisfies the acceptance
                    criterion that duplicate Remita callbacks produce one payment state.

                    The URL carries a secret path segment. Remita sends no signature header
                    by default, so this is what stops the endpoint being trivially
                    discoverable. It is not the security control; re-verification is.

                    **Public by necessity, and unauthenticated.**
                    """)
    @ApiResponse(responseCode = "200",
            description = "Received. Always 200; the outcome is in the inbox record.")
    public ResponseEntity<String> remita(@PathVariable String secret,
                                         @RequestBody String payload,
                                         HttpServletRequest http) {

        if (!constantTimeEquals(secret, remitaProperties.getWebhookSecret())) {
            // Deliberately still 200. A 401 tells whoever is probing that the
            // path exists and the secret was wrong, which is a hint worth not
            // giving. The attempt is logged.
            log.warn("Remita callback with a wrong secret from {}", clientIp(http));
            return ResponseEntity.ok("received");
        }

        try {
            paymentService.handleRemitaCallback(payload, clientIp(http));
        } catch (Exception e) {
            // Swallowed on purpose. Returning an error makes Remita retry a
            // callback we have already stored, and the stored copy is what
            // matters.
            log.error("Remita callback handling failed: {}", e.getMessage(), e);
        }
        return ResponseEntity.ok("received");
    }

    private boolean constantTimeEquals(String presented, String expected) {
        if (presented == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
    }
}
