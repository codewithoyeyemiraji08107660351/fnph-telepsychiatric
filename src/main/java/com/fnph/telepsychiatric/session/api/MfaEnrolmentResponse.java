package com.fnph.telepsychiatric.session.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "MfaEnrolment",
        description = "What the client needs to show while the user sets up an authenticator app.")
public record MfaEnrolmentResponse(

        @Schema(description = """
                The shared secret, base32. Show it as text beside the QR code so a
                user whose camera does not work can type it in.

                Returned once, during enrolment, and never retrievable again.
                """,
                example = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP")
        String secret,

        @Schema(description = "Render this as a QR code. Any authenticator app reads it.",
                example = "otpauth://totp/FNPH%20Kaduna%20Telepsychiatry:a.bello@fnphkaduna.gov.ng?secret=JBSW…")
        String provisioningUri,

        @Schema(description = "Populated only after activation succeeds. Save these; they cannot "
                + "be shown again.", nullable = true)
        List<String> recoveryCodes
) {
}
