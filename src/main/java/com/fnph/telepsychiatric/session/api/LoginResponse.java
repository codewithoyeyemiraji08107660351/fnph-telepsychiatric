package com.fnph.telepsychiatric.session.api;

import com.fnph.telepsychiatric.security.SecurityUser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "LoginResponse",
        description = """
                The result of a sign-in step. Read `status` first; it decides what
                the client does next and which other fields are populated.

                * `AUTHENTICATED` — signed in. Use `accessToken` and `refreshToken`,
                  navigate to `dashboardRoute`.
                * `MFA_REQUIRED` — password accepted, second factor needed. Send
                  `mfaToken` and the six-digit code to `/auth/mfa/verify`.
                * `MFA_ENROLMENT_REQUIRED` — password accepted, but this staff or
                  centre account has no second factor yet. Call `/auth/mfa/enrol`
                  with `mfaToken`, show the QR code, then `/auth/mfa/activate`.

                `mfaToken` grants nothing on its own. It is accepted only on the MFA
                endpoints and carries no role or permission claims.
                """)
public record LoginResponse(

        @Schema(description = "What happened, and therefore what to do next.",
                allowableValues = {"AUTHENTICATED", "MFA_REQUIRED", "MFA_ENROLMENT_REQUIRED"},
                example = "AUTHENTICATED")
        String status,

        @Schema(description = "Bearer token for API calls. Expires in 15 minutes. "
                + "Present only when status is AUTHENTICATED.",
                nullable = true)
        String accessToken,

        @Schema(description = """
                Used once to obtain a new pair, then discarded.

                Every refresh rotates it. Presenting a token that was already
                rotated revokes every session from this sign-in, because the server
                cannot tell a client retry from a stolen token being used alongside
                the real one. Store it where it cannot be read by other code, and
                never send it to an API other than `/auth/refresh`.
                """, nullable = true)
        String refreshToken,

        @Schema(description = "Seconds until the access token expires.", example = "900", nullable = true)
        Long expiresInSeconds,

        @Schema(description = "Short-lived token for the MFA endpoints. Present when status is "
                + "MFA_REQUIRED or MFA_ENROLMENT_REQUIRED.", nullable = true)
        String mfaToken,

        @Schema(description = "Seconds until the MFA challenge expires.", example = "300", nullable = true)
        Long mfaExpiresInSeconds,

        @Schema(description = "Navigate here. No role selector is shown to ordinary users.",
                example = "/clinical", nullable = true)
        String dashboardRoute,

        @Schema(description = "The role that decided the destination.", example = "DOCTOR", nullable = true)
        String primaryRole,

        @Schema(description = "When true, send the user to a password change before anything else.",
                example = "false", nullable = true)
        Boolean mustChangePassword,

        @Schema(description = """
                Single-use recovery codes, returned exactly once when a second factor
                is first enrolled and never retrievable again.

                Tell the user to save them somewhere other than the phone holding the
                authenticator. If both are lost, an administrator has to reset the
                factor, which is the correct trade: a code retrievable later is a code
                an attacker can retrieve too.
                """, nullable = true)
        List<String> recoveryCodes
) {

    public static LoginResponse authenticated(String accessToken, String refreshToken,
                                              long expiresInSeconds, SecurityUser principal) {
        return new LoginResponse("AUTHENTICATED", accessToken, refreshToken, expiresInSeconds,
                null, null, principal.getDashboardRoute(), principal.getPrimaryRole(),
                principal.isMustChangePassword(), null);
    }

    public static LoginResponse mfaRequired(String mfaToken, long mfaExpiresInSeconds) {
        return new LoginResponse("MFA_REQUIRED", null, null, null,
                mfaToken, mfaExpiresInSeconds, null, null, null, null);
    }

    public static LoginResponse mfaEnrolmentRequired(String mfaToken, long mfaExpiresInSeconds) {
        return new LoginResponse("MFA_ENROLMENT_REQUIRED", null, null, null,
                mfaToken, mfaExpiresInSeconds, null, null, null, null);
    }

    public LoginResponse withRecoveryCodes(List<String> codes) {
        return new LoginResponse(status, accessToken, refreshToken, expiresInSeconds,
                mfaToken, mfaExpiresInSeconds, dashboardRoute, primaryRole,
                mustChangePassword, codes);
    }
}
