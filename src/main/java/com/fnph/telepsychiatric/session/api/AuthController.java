package com.fnph.telepsychiatric.session.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.session.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication")
public class AuthController {

    private final AuthenticationService authenticationService;

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Sign in with username and password",
            description = """
                    Step one. Read `status` on the response to decide what happens next.

                    * `AUTHENTICATED` — done. Use the tokens, navigate to `dashboardRoute`.
                    * `MFA_REQUIRED` — send `mfaToken` and a code to `/auth/mfa/verify`.
                    * `MFA_ENROLMENT_REQUIRED` — this staff or centre account has no second
                      factor yet. Call `/auth/mfa/enrol`, show the QR code, then
                      `/auth/mfa/activate`.

                    **Every failure returns the same message.** Wrong password, unknown
                    username, locked account and unactivated invitation are
                    indistinguishable from the response and from its timing. Telling them
                    apart would turn this endpoint into a way to discover which staff and
                    which EHR numbers hold accounts at a neuropsychiatric hospital, which
                    is a disclosure in itself. Administrators can see the real outcome in
                    the sign-in log.

                    **Rate limited two ways.** Five failures against one username in
                    fifteen minutes locks that account for thirty. Thirty failures from
                    one address across any usernames blocks the address. Both are needed:
                    locking only by username lets an attacker spray one password across
                    many accounts, and it also lets them lock a named clinician out of a
                    clinical system on demand.

                    **Public.** No token required.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Password accepted. Check `status` for what to do next.",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Sign-in failed. The message is deliberately the same for every cause.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429",
                    description = "Too many attempts against this username or from this address.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest http) {
        return ResponseEntity.ok(authenticationService.login(request, context(http, request.deviceLabel())));
    }

    @PostMapping("/mfa/verify")
    @SecurityRequirements
    @Operation(
            summary = "Complete sign-in with a second factor",
            description = """
                    Step two, for an account that already has an authenticator enrolled.

                    Accepts either a six-digit code from the app or one recovery code.
                    Recovery codes contain a hyphen, which is how they are told apart, and
                    each works exactly once.

                    A code from the previous or next 30-second window is accepted, which
                    covers clock drift on a phone and the time it takes to read six digits
                    and type them. Five wrong codes locks the factor for fifteen minutes.

                    **Public**, but requires the `mfaToken` from the login step, which
                    expires in five minutes and grants nothing else.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in.",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Wrong code, or the sign-in attempt expired.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LoginResponse> verifyMfa(@Valid @RequestBody MfaVerificationRequest request,
                                                   HttpServletRequest http) {
        return ResponseEntity.ok(authenticationService.verifyMfa(request, context(http, null)));
    }

    @PostMapping("/mfa/enrol")
    @SecurityRequirements
    @Operation(
            summary = "Start setting up an authenticator app",
            description = """
                    Returns a shared secret and an `otpauth://` URI.

                    Render the URI as a QR code and show the secret as text beside it, for
                    a user whose camera does not work. Any authenticator app reads it:
                    Google Authenticator, Microsoft Authenticator, Authy, 1Password.

                    The factor is created inactive and does nothing until
                    `/auth/mfa/activate` confirms the user can generate a code, so an
                    abandoned enrolment cannot lock the account out.

                    A second factor is required for every staff and centre account. It is
                    not required for patients: making someone enrol a TOTP app to attend a
                    psychiatric appointment is a barrier that would stop people attending,
                    and a patient account cannot approve bookings, move money or read
                    anyone else's record.

                    **Public**, but requires the `mfaToken` from the login step.
                    """)
    @ApiResponse(responseCode = "200", description = "Secret and provisioning URI returned.",
            content = @Content(schema = @Schema(implementation = MfaEnrolmentResponse.class)))
    public ResponseEntity<MfaEnrolmentResponse> beginMfaEnrolment(
            @Parameter(description = "The `mfaToken` from the login step.", required = true)
            @RequestParam String mfaToken) {
        return ResponseEntity.ok(authenticationService.beginMfaEnrolment(mfaToken));
    }

    @PostMapping("/mfa/activate")
    @SecurityRequirements
    @Operation(
            summary = "Confirm the authenticator and finish signing in",
            description = """
                    Verifies a code from the newly added app, activates the factor, and
                    signs the user in.

                    The response carries `recoveryCodes` **once**. They are stored hashed
                    and can never be shown again. Tell the user to save them somewhere
                    other than the phone holding the authenticator; if both are lost, an
                    administrator has to reset the factor. That is the correct trade,
                    because a recovery code retrievable later is one an attacker can
                    retrieve too.

                    **Public**, but requires the `mfaToken` from the login step.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Enrolled and signed in. Save the recovery codes now.",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "400", description = "The code did not verify.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LoginResponse> completeMfaEnrolment(
            @Valid @RequestBody MfaVerificationRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(authenticationService.completeMfaEnrolment(request, context(http, null)));
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(
            summary = "Exchange a refresh token for a new pair",
            description = """
                    Access tokens last fifteen minutes. Call this when one expires, or
                    shortly before.

                    **The refresh token is rotated on every call.** The one you send stops
                    working and a new one comes back. Store the new one immediately.

                    **Presenting a token that was already rotated ends every session from
                    that sign-in.** That is either a client retrying after a dropped
                    response or a stolen token being used alongside the real one, and the
                    server cannot tell which, so it assumes the worse case. Occasionally
                    losing a session is a much better outcome than letting a stolen token
                    run for seven days against psychiatric records.

                    Also returns 401 when the session has been idle past the inactivity
                    timeout, or revoked by the user, an administrator, a password change
                    or account deactivation.

                    **Public.** The refresh token is the credential.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New token pair issued.",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Session ended. Send the user back to sign-in.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                                 HttpServletRequest http) {
        return ResponseEntity.ok(authenticationService.refresh(request.refreshToken(), context(http, null)));
    }

    @PostMapping("/logout")
    @SecurityRequirements
    @Operation(
            summary = "Sign out on this device",
            description = """
                    Revokes the session behind this refresh token. Other devices stay
                    signed in; use `DELETE /api/v1/sessions` to end those.

                    Always returns 204, including for a token that was already invalid.
                    Signing out is not somewhere to learn whether a token was real.
                    """)
    @ApiResponse(responseCode = "204", description = "Signed out.")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authenticationService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/activation")
    @SecurityRequirements
    @Operation(
            summary = "Check an invitation link before showing the password form",
            description = """
                    Confirms the link is valid and returns who it belongs to, so the setup
                    page can greet the person by name rather than asking them to identify
                    themselves again.

                    Returns 400 for a link that expired, was already used, or was replaced
                    by a newer one. Ask the administrator to resend.

                    **Public.** The token is the credential.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Link is valid.",
                    content = @Content(schema = @Schema(implementation = ActivationPreviewResponse.class))),
            @ApiResponse(responseCode = "400", description = "Expired, used, or replaced.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ActivationPreviewResponse> previewActivation(
            @Parameter(description = "Token from the invitation link.", required = true)
            @RequestParam String token) {
        return ResponseEntity.ok(authenticationService.previewActivation(token));
    }

    @PostMapping("/activation")
    @SecurityRequirements
    @Operation(
            summary = "Finish setting up an invited account",
            description = """
                    Sets the password, activates the account and marks the email address
                    verified, because following the link proves the address reaches this
                    person. That verification is what later allows a password reset to be
                    sent there.

                    No tokens are returned. The user signs in normally afterwards, which
                    for a staff or centre account means enrolling a second factor on that
                    first sign-in.

                    **Public.** The token is the credential.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account activated. Send the user to sign in."),
            @ApiResponse(responseCode = "400",
                    description = "Link no longer valid, or the password does not meet the policy.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> completeActivation(
            @Valid @RequestBody CompleteActivationRequest request, HttpServletRequest http) {
        authenticationService.completeActivation(request, context(http, null));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/forgot")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Ask for a password reset link",
            description = """
                    **Always returns 202**, whether or not the account exists.

                    Replying differently for a known and an unknown address would turn
                    this into a way to check who holds an account here. The response and
                    roughly the timing are identical either way.

                    No email is sent when the address was never verified, because an
                    address that was mistyped when the account was created would otherwise
                    hand the account to whoever owns it.

                    The link lasts one hour and works once. Requesting another invalidates
                    the previous one immediately.

                    **Public.**
                    """)
    @ApiResponse(responseCode = "202",
            description = "Request accepted. An email is sent only if the account exists and its "
                    + "address is verified.")
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                               HttpServletRequest http) {
        authenticationService.requestPasswordReset(request.identifier(), context(http, null));
    }

    @PostMapping("/password/reset")
    @SecurityRequirements
    @Operation(
            summary = "Set a new password using a reset link",
            description = """
                    Sets the password, clears any lockout, and **signs the account out
                    everywhere including the current device**.

                    Signing out everywhere is the point: if someone else forced this
                    reset, they may hold a live session, and leaving it running would make
                    the reset pointless.

                    A confirmation email is sent, so a reset the account holder did not
                    request reaches them.

                    **Public.** The token is the credential.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password set. Sign in again."),
            @ApiResponse(responseCode = "400",
                    description = "Link no longer valid, or the password does not meet the policy.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                              HttpServletRequest http) {
        authenticationService.resetPassword(request, context(http, null));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/change")
    @Operation(
            summary = "Change your own password",
            description = """
                    Requires the current password, so someone walking up to an unlocked
                    workstation cannot silently take over the account.

                    Signs out every other device but keeps this one, and sends a
                    confirmation email.

                    **Requires** any authenticated session.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password changed."),
            @ApiResponse(responseCode = "400",
                    description = "Current password wrong, new password reused, or policy not met.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Not signed in.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletRequest http) {
        authenticationService.changePassword(CurrentUser.require(), request, context(http, null));
        return ResponseEntity.noContent().build();
    }

    private AuthenticationService.RequestContext context(HttpServletRequest http, String deviceLabel) {
        return new AuthenticationService.RequestContext(
                clientIp(http), http.getHeader("User-Agent"), deviceLabel,
                CurrentUser.usernameOrSystem());
    }

    /**
     * Behind Nginx the socket address is the proxy, so the forwarded header is
     * the real client. Only the first entry is trusted, and only because the
     * proxy is configured to overwrite rather than append the header. If that
     * ever changes, this becomes spoofable and rate limiting becomes bypassable.
     */
    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
