package com.fnph.telepsychiatric.session.api;

import com.fnph.telepsychiatric.authz.api.CurrentPrincipalResponse;
import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.SecurityUser;
import com.fnph.telepsychiatric.session.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Session")
public class SessionController {

    private final CenterRepository centreRepository;
    private final SessionService sessionService;

    @GetMapping("/me")
    @Operation(
            summary = "Who am I, and what may I do",
            description = """
                    Everything a client needs to render the correct interface, in one call
                    made immediately after authentication.

                    **Navigate to `dashboardRoute` and nothing else.** The specification
                    requires an ordinary user to be routed directly to their assigned
                    dashboard with no role selector displayed, so the destination comes
                    from the server rather than from a branch in the client.

                    **Drive control visibility from `permissions`.** That is a convenience
                    for the interface, not a security boundary. Every one of these is
                    enforced independently by the server on each request, so a client that
                    shows a control the user cannot use gains nothing by doing so.

                    **Check `mustChangePassword` before anything else.** When true, send
                    the user to a password change and block the rest of the interface.

                    `centrePublicId` and `centreName` are present for centre staff only.
                    Every request from a centre account is constrained to that centre in
                    the repository layer; the value is returned here for display, never as
                    something the client sends back to select a tenant.

                    **Requires** any authenticated session.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current principal returned.",
                    content = @Content(schema = @Schema(implementation = CurrentPrincipalResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "No valid access token. Refresh, or send the user back to sign-in.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CurrentPrincipalResponse> me() {
        SecurityUser user = CurrentUser.require();

        String centrePublicId = null;
        String centreName = null;
        if (user.getCentreId() != null) {
            var centre = centreRepository.findById(user.getCentreId()).orElse(null);
            if (centre != null) {
                centrePublicId = centre.getPublicId();
                centreName = centre.getName();
            }
        }

        return ResponseEntity.ok(new CurrentPrincipalResponse(
                user.getPublicId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getScope().name(),
                user.getPrimaryRole(),
                user.getDashboardRoute(),
                List.copyOf(user.getRoles()),
                user.getPermissions().stream().sorted().toList(),
                centrePublicId,
                centreName,
                user.isMfaEnabled(),
                user.isMustChangePassword()));
    }

    @GetMapping("/sessions")
    @Operation(
            summary = "List the devices signed into my account",
            description = """
                    Every active session, most recently used first.

                    Show this to users. It is how someone notices a sign-in they did not
                    make, and it is the only way they can act on it without waiting for an
                    administrator.

                    `lastSeenAt` is what the inactivity timeout is measured from, not
                    `signedInAt`, so an actively used session is not cut off at a fixed
                    interval while an abandoned one still expires.

                    **Requires** any authenticated session.
                    """)
    @ApiResponse(responseCode = "200", description = "Active sessions returned.")
    public ResponseEntity<List<SessionResponse>> mySessions() {
        SecurityUser user = CurrentUser.require();
        return ResponseEntity.ok(sessionService.listActiveSessions(user.getUserId()).stream()
                .map(s -> new SessionResponse(
                        s.getPublicId(), s.getDeviceLabel(), s.getIpAddress(), s.getUserAgent(),
                        s.getIssuedAt(), s.getLastSeenAt(), s.getExpiresAt(), false))
                .toList());
    }

    @DeleteMapping("/sessions/{sessionPublicId}")
    @Operation(
            summary = "Sign out one device",
            description = """
                    Revokes a single session immediately. Its refresh token stops working
                    at once, and its access token stops working when it expires, within
                    fifteen minutes.

                    You can only revoke your own sessions here. An administrator holding
                    `session.revoke` uses the admin endpoint to revoke someone else's.

                    **Requires** any authenticated session.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Session revoked."),
            @ApiResponse(responseCode = "404",
                    description = "No such session on this account. Deliberately not distinguished "
                            + "from a session belonging to someone else.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> revokeSession(
            @Parameter(description = "The session's public identifier.", required = true)
            @PathVariable String sessionPublicId) {
        SecurityUser user = CurrentUser.require();
        boolean revoked = sessionService.revokeByPublicId(
                user.getUserId(), sessionPublicId, "Revoked by the account holder");
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/sessions")
    @Operation(
            summary = "Sign out every device",
            description = """
                    Revokes every session on this account, including the one making the
                    request. The user has to sign in again here too.

                    Point users at this the moment they suspect someone else has their
                    password. Combine it with a password change: this ends the sessions,
                    the password change stops new ones being created.

                    **Requires** any authenticated session.
                    """)
    @ApiResponse(responseCode = "204", description = "All sessions revoked. Sign in again.")
    public ResponseEntity<Void> revokeAllSessions() {
        SecurityUser user = CurrentUser.require();
        sessionService.revokeAll(user.getUserId(), null, "Signed out of all devices by the account holder");
        return ResponseEntity.noContent().build();
    }
}
