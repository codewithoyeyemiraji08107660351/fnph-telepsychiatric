package com.fnph.telepsychiatric.user.api;

import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.session.AuthenticationService;
import com.fnph.telepsychiatric.session.MfaService;
import com.fnph.telepsychiatric.session.SessionService;
import com.fnph.telepsychiatric.user.StaffInvitationService;
import com.fnph.telepsychiatric.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Tag(name = "Administration — Staff & Centre Accounts")
public class AdminUserController {

    private final StaffInvitationService invitationService;
    private final SessionService sessionService;
    private final MfaService mfaService;
    private final UserRepository userRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).USER_CREATE)")
    @Operation(
            summary = "Create a staff or centre account and send an invitation",
            description = """
                    Creates the account in `INVITED` state and emails a single-use setup
                    link. The account cannot sign in until that link is used.

                    **No password is generated or sent.** The recipient chooses their own
                    through the link. A generated password would sit in that inbox and in
                    the mail provider's storage for the life of the account, neither of
                    which is under FNPH's control.

                    **Patient accounts cannot be created here.** Passing a PATIENT role is
                    rejected with 400. Patients enrol themselves by verifying an existing
                    FNPH EHR number and are never sent an unsolicited email, because an
                    email naming someone as a patient of this hospital discloses their
                    care to anyone with access to that inbox: a shared family address, a
                    work account, a phone showing previews on a locked screen. That
                    disclosure would happen before the person had agreed to anything.

                    A second factor is required for every account created here and is
                    enrolled at first sign-in.

                    Centre roles require `centrePublicId`. The three optional local roles
                    also require that capability to be activated at the centre first.

                    The invitation link lasts 72 hours, which allows for a new member of
                    staff who is not at a desk today.

                    **Requires** `user.create`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created and invitation sent.",
                    content = @Content(schema = @Schema(implementation = StaffUserResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "PATIENT role rejected, username or email already in use, "
                            + "centre missing for a centre role, or the optional local role is not "
                            + "activated at that centre.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Lacking `user.create`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<StaffUserResponse> invite(@Valid @RequestBody CreateStaffUserRequest request,
                                                    HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitationService.invite(request, context(http)));
    }

    @PostMapping("/{userPublicId}/invitation")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).USER_CREATE)")
    @Operation(
            summary = "Resend an invitation",
            description = """
                    Issues a fresh link and emails it again. **The previous link stops
                    working immediately**, so a forwarded or intercepted old email is
                    dead as soon as a new one is sent.

                    Only for accounts still in `INVITED` state. For an account that has
                    already been taken up, send a password reset instead.

                    **Requires** `user.create`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New invitation sent.",
                    content = @Content(schema = @Schema(implementation = StaffUserResponse.class))),
            @ApiResponse(responseCode = "400", description = "That account is already active.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No user with that identifier.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<StaffUserResponse> resendInvitation(
            @Parameter(description = "The account's public identifier.", required = true)
            @PathVariable String userPublicId,
            HttpServletRequest http) {
        return ResponseEntity.ok(invitationService.resendInvitation(userPublicId, context(http)));
    }

    @PostMapping("/{userPublicId}/deactivate")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).USER_DEACTIVATE)")
    @Operation(
            summary = "Remove an account's access",
            description = """
                    Sets the account to `DEACTIVATED`, ends every session immediately and
                    emails the account holder with the reason given.

                    **Nothing is erased.** Clinical, financial and audit records
                    referencing this account stay exactly as they are, which is why this
                    is a status change and not a delete. That is a stated non-negotiable
                    control: a consultation note must remain attributable to the clinician
                    who wrote it long after they have left.

                    **Requires** `user.deactivate`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Access removed, sessions ended, history intact."),
            @ApiResponse(responseCode = "404", description = "No user with that identifier.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> deactivate(@PathVariable String userPublicId,
                                           @Valid @RequestBody DeactivateUserRequest request) {
        invitationService.deactivate(userPublicId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userPublicId}/sessions")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).SESSION_REVOKE)")
    @Operation(
            summary = "Sign another account out of every device",
            description = """
                    For a lost or stolen device, or a suspected compromise, when the
                    account holder cannot do it themselves.

                    Ends every session at once. Access tokens already issued stop working
                    when they expire, within fifteen minutes.

                    This does not change the password. If the account is believed
                    compromised, deactivate it or trigger a reset as well, or the same
                    credentials will simply be used to sign in again.

                    **Requires** `session.revoke`, held by the Central Administrator and
                    ICT Support.
                    """)
    @ApiResponse(responseCode = "200", description = "Returns how many sessions were ended.")
    public ResponseEntity<String> revokeAllSessions(@PathVariable String userPublicId) {
        var user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "No user with id " + userPublicId));
        int count = sessionService.revokeAll(user.getId(), null,
                "Revoked by " + CurrentUser.usernameOrSystem());
        return ResponseEntity.ok(count + " sessions ended");
    }

    @DeleteMapping("/{userPublicId}/mfa")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).MFA_RESET)")
    @Operation(
            summary = "Reset a second factor",
            description = """
                    For a lost phone with no recovery codes left. Removes the enrolled
                    authenticator and every recovery code, so the user enrols again at
                    their next sign-in.

                    **Verify who you are talking to before using this.** It is the one
                    control that removes the second factor on a clinical account, which
                    makes it the obvious target for a phone call claiming to be a
                    colleague who has lost their phone. Every use is logged with the
                    administrator's identity.

                    **Requires** `mfa.reset`, held by the Central Administrator and ICT
                    Support.
                    """)
    @ApiResponse(responseCode = "204", description = "Factor reset. The user enrols again at next sign-in.")
    public ResponseEntity<Void> resetMfa(@PathVariable String userPublicId) {
        var user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "No user with id " + userPublicId));
        mfaService.resetFactor(user.getId());
        sessionService.revokeAll(user.getId(), null, "Second factor reset");
        return ResponseEntity.noContent().build();
    }

    private AuthenticationService.RequestContext context(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
        return new AuthenticationService.RequestContext(
                ip, http.getHeader("User-Agent"), null, CurrentUser.usernameOrSystem());
    }
}
