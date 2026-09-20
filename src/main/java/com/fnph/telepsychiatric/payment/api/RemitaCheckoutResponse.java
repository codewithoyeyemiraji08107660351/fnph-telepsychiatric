package com.fnph.telepsychiatric.payment.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RemitaCheckout", description = "One-time form values for Remita's hosted RRR payment page.")
public record RemitaCheckoutResponse(
        String url,
        String merchantId,
        String rrr,
        String hash,
        String responseUrl
) {
}
