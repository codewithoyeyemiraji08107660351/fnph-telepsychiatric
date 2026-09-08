package com.fnph.telepsychiatric.session.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "RefreshRequest", description = "Exchange a refresh token for a new pair.")
public record RefreshRequest(

        @NotBlank(message = "The refresh token is required")
        @Schema(description = "The refresh token from the last successful call. "
                + "It is consumed by this request and replaced.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String refreshToken
) {
}
