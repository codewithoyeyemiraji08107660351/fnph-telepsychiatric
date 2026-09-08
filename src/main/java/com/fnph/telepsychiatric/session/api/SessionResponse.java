package com.fnph.telepsychiatric.session.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "Session", description = "One device signed into this account.")
public record SessionResponse(

        @Schema(description = "Identifier to pass when revoking this session.",
                example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(description = "Label given at sign-in, if any.", example = "Chrome on the clinic desktop",
                nullable = true)
        String deviceLabel,

        @Schema(description = "Address it signed in from.", example = "197.210.0.0", nullable = true)
        String ipAddress,

        @Schema(description = "Browser or app reported at sign-in.", nullable = true)
        String userAgent,

        @Schema(description = "When this device signed in (UTC).")
        LocalDateTime signedInAt,

        @Schema(description = "Last request from this device (UTC). Inactivity is measured from here.")
        LocalDateTime lastSeenAt,

        @Schema(description = "When it expires without further use (UTC).")
        LocalDateTime expiresAt,

        @Schema(description = "True for the device making this request. Revoking it signs you out here.",
                example = "true")
        boolean current
) {
}
