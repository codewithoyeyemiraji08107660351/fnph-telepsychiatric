package com.fnph.telepsychiatric.notification.api;

import com.fnph.telepsychiatric.authz.RoleRepository;
import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.notification.*;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/notifications")
@RequiredArgsConstructor
@Tag(name = "Administration — Notifications")
public class NotificationAdminController {

    private final NotificationTemplateRepository templateRepository;
    private final NotificationBroadcastRepository broadcastRepository;
    private final InAppNotificationService notificationService;
    private final RoleRepository roleRepository;
    private final CenterRepository centreRepository;
    private final UserRepository userRepository;

    @GetMapping("/templates")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).NOTIFICATION_MANAGE_TEMPLATES)")
    @Operation(
            summary = "Notification wording",
            description = """
                    Every template that has been customised.

                    **A type with no row here is not broken.** It falls back to the wording
                    in the code, which is why an empty list is the normal starting state. An
                    empty table must not mean an empty notification.

                    Account emails are not here. Those are Thymeleaf files carrying layout
                    and a hospital letterhead rather than a sentence.

                    **Requires** `notification.manage_templates`.
                    """)
    @ApiResponse(responseCode = "200", description = "Templates returned.")
    public ResponseEntity<List<Map<String, Object>>> templates() {
        return ResponseEntity.ok(templateRepository.findAllByOrderByNotificationTypeAsc()
                .stream().map(t -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("publicId", t.getPublicId());
                    row.put("notificationType", t.getNotificationType().name());
                    row.put("channel", t.getChannel().name());
                    row.put("subject", t.getSubject());
                    row.put("body", t.getBody());
                    row.put("active", t.getIsActive());
                    row.put("updatedReason", t.getUpdatedReason());
                    return row;
                }).toList());
    }

    @PutMapping("/templates/{notificationType}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).NOTIFICATION_MANAGE_TEMPLATES)")
    @Operation(
            summary = "Change the wording of a notification",
            description = """
                    Creates or replaces the template for a type and channel.

                    **Refused if the body looks clinical.** A notification arrives on a phone
                    that may be sitting on a shared clinic desk, so it says a document is
                    ready and never what it is for. The check looks for words that should
                    not appear in a message the recipient has not authenticated to read.

                    A reason is required. Six months from now somebody will ask why a
                    message says what it says.

                    **Requires** `notification.manage_templates`, held by the Central
                    Administrator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Wording updated."),
            @ApiResponse(responseCode = "400",
                    description = "The body contains clinical wording, or no reason was given.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Map<String, Object>> updateTemplate(
            @PathVariable NotificationType notificationType,
            @RequestParam(defaultValue = "IN_APP") NotificationChannel channel,
            @RequestParam String subject,
            @RequestParam String body,
            @Parameter(description = "Why the wording is changing.", required = true)
            @RequestParam String reason) {

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Say why the wording is changing. Somebody will ask.");
        }
        assertNotClinical(body);
        assertNotClinical(subject);

        NotificationTemplate template = templateRepository
                .findByNotificationTypeAndChannelAndIsActiveTrue(notificationType, channel)
                .orElseGet(() -> {
                    NotificationTemplate created = new NotificationTemplate();
                    created.setNotificationType(notificationType);
                    created.setChannel(channel);
                    return created;
                });

        template.setSubject(subject);
        template.setBody(body);
        template.setUpdatedReason(reason);
        template.setIsActive(true);
        templateRepository.save(template);

        return ResponseEntity.ok(Map.of(
                "notificationType", notificationType.name(),
                "channel", channel.name(),
                "updated", true));
    }

    /**
     * A crude check, and worth having.
     *
     * It cannot tell whether a sentence is clinical, but it catches the obvious
     * mistake of pasting a diagnosis or a drug name into a message the
     * recipient does not have to sign in to read.
     */
    private void assertNotClinical(String text) {
        String lower = text.toLowerCase();
        List<String> suspicious = List.of("diagnos", "prescri", "mg ", "dose", "psychosis",
                "depress", "schizophren", "medication:", "symptom");

        String hit = suspicious.stream().filter(lower::contains).findFirst().orElse(null);
        if (hit != null) {
            throw new IllegalArgumentException(
                    "That wording contains \"" + hit.trim() + "\", which reads as clinical "
                            + "detail. A notification appears on a device the recipient has "
                            + "not signed in to, often on a shared desk. Say that something "
                            + "is ready and let them open the record to see what.");
        }
    }

    @PostMapping("/broadcast")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).NOTIFICATION_SEND)")
    @Operation(
            summary = "Send an announcement",
            description = """
                    For a planned outage, a change of process, or a service notice.

                    **Target a role, and a centre if it is centre-specific.** Leaving both
                    empty sends to every account in the system, which is almost never what
                    somebody means and is recorded plainly for that reason.

                    Same clinical check as a template. An announcement is not a route to
                    telling somebody something about a patient.

                    Every broadcast is recorded with who sent it, why, and how many people
                    received it. "Everyone was told on Tuesday" is a claim somebody will
                    check.

                    **Requires** `notification.send`, held by the Central Administrator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sent, with a recipient count."),
            @ApiResponse(responseCode = "400", description = "Clinical wording, or no reason.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @Transactional
    public ResponseEntity<Map<String, Object>> broadcast(
            @RequestParam String subject,
            @RequestParam String body,
            @Parameter(description = "Role to target. Omit to reach everyone.")
            @RequestParam(required = false) String targetRole,
            @Parameter(description = "Restrict a role-targeted notice to one centre.")
            @RequestParam(required = false) String centrePublicId,
            @Parameter(required = true) @RequestParam String reason) {

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Say why this is being sent");
        }
        assertNotClinical(subject);
        assertNotClinical(body);

        var role = targetRole == null ? null : roleRepository.findByCode(targetRole)
                .orElseThrow(() -> new EntityNotFoundException("No such role"));
        var centre = centrePublicId == null ? null : centreRepository.findByPublicId(centrePublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such centre"));

        int recipients;
        if (role != null) {
            // One row addressed to the role, seen by every holder. Not a copy
            // per person, for the same reason the approval queue is not.
            notificationService.notifyRole(role.getCode(), centre,
                    NotificationType.SYSTEM_ALERT, subject, body, null, "Broadcast", null);
            recipients = 1;
        } else {
            // Everyone. Deliberately one row per account here, because there is
            // no single role to address and a shared row would be seen by
            // nobody.
            var everyone = userRepository.findAll();
            everyone.forEach(user -> notificationService.notifyUser(user,
                    NotificationType.SYSTEM_ALERT, subject, body, null, "Broadcast", null));
            recipients = everyone.size();
        }

        NotificationBroadcast record = new NotificationBroadcast();
        record.setSubject(subject);
        record.setBody(body);
        record.setTargetRole(role);
        record.setCentre(centre);
        record.setSentBy(CurrentUser.usernameOrSystem());
        record.setSentAt(LocalDateTime.now());
        record.setRecipientCount(recipients);
        record.setReason(reason);
        broadcastRepository.save(record);

        return ResponseEntity.ok(Map.of(
                "sent", true,
                "target", role == null ? "EVERYONE" : role.getCode(),
                "recipients", recipients));
    }

    @GetMapping("/broadcasts")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).NOTIFICATION_SEND)")
    @Operation(summary = "Announcements already sent",
            description = "The last fifty, newest first, with who sent each and why.\n\n"
                    + "**Requires** `notification.send`.")
    @ApiResponse(responseCode = "200", description = "Broadcasts returned.")
    public ResponseEntity<List<Map<String, Object>>> broadcasts() {
        return ResponseEntity.ok(broadcastRepository.findTop50ByOrderBySentAtDesc()
                .stream().map(b -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("subject", b.getSubject());
                    row.put("target", b.getTargetRole() == null
                            ? "EVERYONE" : b.getTargetRole().getCode());
                    row.put("centre", b.getCentre() == null ? null : b.getCentre().getName());
                    row.put("sentBy", b.getSentBy());
                    row.put("sentAt", b.getSentAt());
                    row.put("recipients", b.getRecipientCount());
                    row.put("reason", b.getReason());
                    return row;
                }).toList());
    }
}
