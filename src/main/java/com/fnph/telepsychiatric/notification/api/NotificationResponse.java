package com.fnph.telepsychiatric.notification.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "Notification", description = "One item in a dashboard inbox.")
public record NotificationResponse(

        @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(example = "APPOINTMENT_APPROVED")
        String type,

        @Schema(example = "Payment confirmed")
        String subject,

        @Schema(description = "Never contains clinical detail. This list is visible on a "
                + "shared clinic screen.",
                example = "Your payment has been confirmed. You can now choose an appointment time.")
        String body,

        @Schema(description = "Where clicking it should go.", example = "/portal/booking",
                nullable = true)
        String actionUrl,

        @Schema(example = "Payment", nullable = true)
        String entityType,

        @Schema(nullable = true)
        Long entityId,

        @Schema(description = """
                True when this belongs to a dashboard rather than to one person.

                Marking it read removes it from every colleague's list too, because it is \
                one piece of work, not one message each.
                """,
                example = "false")
        boolean sharedWithRole,

        @Schema(description = "The role it is addressed to, when shared.",
                example = "HUB_COORDINATOR", nullable = true)
        String targetRole,

        @Schema(example = "false")
        boolean read,

        @Schema(description = "Who picked up a shared item.", example = "coord.musa", nullable = true)
        String acknowledgedBy,

        LocalDateTime createdAt
) {
}
