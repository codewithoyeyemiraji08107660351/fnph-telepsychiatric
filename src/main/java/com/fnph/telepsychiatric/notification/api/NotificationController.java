package com.fnph.telepsychiatric.notification.api;

import com.fnph.telepsychiatric.notification.InAppNotificationService;
import com.fnph.telepsychiatric.notification.Notification;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.SecurityUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications")
public class NotificationController {

    private final InAppNotificationService notificationService;

    @GetMapping
    @Operation(
            summary = "My dashboard notifications",
            description = """
                    Everything waiting for this principal: items addressed to them
                    personally, plus items addressed to their dashboard.

                    **Dashboard items are shared, not copied.** A booking arriving for
                    approval belongs to whoever is on the desk, not to a named coordinator.
                    One row is created and every holder of the role sees it, so the unread
                    count is the amount of work waiting rather than a per-person copy of
                    it, and it drops for everyone as soon as one person picks it up.

                    A centre role sees only its own centre's items. That is enforced in the
                    query, not left to the client.

                    **No clinical detail.** A notification says a prescription is ready,
                    never what it is for. This list is visible on a shared clinic screen,
                    and anything more requires opening the record itself with the
                    permission to read it.

                    Follow `actionUrl` on click.

                    **Requires** any authenticated session.
                    """)
    @ApiResponse(responseCode = "200", description = "Notifications returned, newest first.",
            content = @Content(schema = @Schema(implementation = NotificationResponse.class)))
    public ResponseEntity<List<NotificationResponse>> inbox(
            @Parameter(description = "Only items not yet read.", example = "false")
            @RequestParam(defaultValue = "false") boolean unreadOnly) {

        SecurityUser user = CurrentUser.require();
        return ResponseEntity.ok(notificationService
                .inbox(user.getUserId(), List.copyOf(user.getRoles()), user.getCentreId(), unreadOnly)
                .stream().map(this::toResponse).toList());
    }

    @GetMapping("/unread-count")
    @Operation(
            summary = "How many items are waiting",
            description = """
                    The badge number. Cheap enough to poll.

                    For a role-addressed queue this is the size of the work backlog, which
                    is the number a coordinator actually needs, rather than how many
                    messages one person has not opened.

                    **Requires** any authenticated session.
                    """)
    @ApiResponse(responseCode = "200", description = "Count returned.")
    public ResponseEntity<Map<String, Long>> unreadCount() {
        SecurityUser user = CurrentUser.require();
        return ResponseEntity.ok(Map.of("unread", notificationService.unreadCount(
                user.getUserId(), List.copyOf(user.getRoles()), user.getCentreId())));
    }

    @PostMapping("/{notificationPublicId}/read")
    @Operation(
            summary = "Mark one item read",
            description = """
                    For a dashboard item this takes it off everyone's list and records who
                    picked it up, which is how a team knows a request is being handled
                    rather than two people opening it at once.

                    **Requires** any authenticated session.
                    """)
    @ApiResponse(responseCode = "204", description = "Marked read.")
    public ResponseEntity<Void> markRead(@PathVariable String notificationPublicId) {
        notificationService.markRead(notificationPublicId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(
            summary = "Mark everything read",
            description = """
                    Clears the badge.

                    Use with care on a shared dashboard: it also clears items addressed to
                    the role, which removes them from every colleague's list as well.

                    **Requires** any authenticated session.
                    """)
    @ApiResponse(responseCode = "200", description = "Returns how many were marked.")
    public ResponseEntity<Map<String, Integer>> markAllRead() {
        SecurityUser user = CurrentUser.require();
        return ResponseEntity.ok(Map.of("marked", notificationService.markAllRead(
                user.getUserId(), List.copyOf(user.getRoles()), user.getCentreId())));
    }

    private NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(
                n.getPublicId(), n.getType().name(), n.getSubject(), n.getBody(),
                n.getActionUrl(), n.getEntityType(), n.getEntityId(),
                n.getTargetRole() != null,
                n.getTargetRole() == null ? null : n.getTargetRole().getCode(),
                n.getReadAt() != null,
                n.getAcknowledgedBy() == null ? null : n.getAcknowledgedBy().getUsername(),
                n.getCreatedAt());
    }
}
