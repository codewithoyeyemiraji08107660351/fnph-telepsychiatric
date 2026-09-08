package com.fnph.telepsychiatric.helpdesk.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "SupportTicket", description = "A support request and its conversation.")
public record TicketResponse(

        @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(description = "Quote this when following up.", example = "TKT-K7M4NPQR8T")
        String ticketNumber,

        @Schema(example = "BOOKING")
        String category,

        @Schema(allowableValues = {"LOW", "NORMAL", "HIGH", "URGENT"}, example = "NORMAL")
        String priority,

        @Schema(description = """
                `AWAITING_REQUESTER` means we are waiting on you. `ESCALATED` means it has \
                gone to another team, and for a clinical concern that is where it stays.
                """,
                allowableValues = {"OPEN", "IN_PROGRESS", "AWAITING_REQUESTER", "ESCALATED",
                        "RESOLVED", "CLOSED"},
                example = "IN_PROGRESS")
        String status,

        @Schema(example = "Cannot join my consultation")
        String subject,

        @Schema(description = "The team it was escalated to.", example = "ICT_SUPPORT",
                nullable = true)
        String escalatedToRole,

        @Schema(description = "How long the first reply took, in minutes. Measured, and used "
                + "for the figure reported to FNPH.",
                example = "6", nullable = true)
        Integer firstResponseMinutes,

        @Schema(nullable = true)
        String resolutionSummary,

        LocalDateTime createdAt,

        @Schema(description = """
                The conversation. **Internal staff notes are not included** for a \
                requester; they are visible only to staff with `ticket.read`.
                """)
        List<Message> messages
) {

    @Schema(name = "TicketMessage", description = "One message on a ticket.")
    public record Message(

            @Schema(description = "Who wrote it. Staff appear as a desk rather than by name.",
                    example = "FNPH Support")
            String author,

            String body,

            @Schema(description = "True for a staff-only note. Never returned to a requester.",
                    example = "false")
            boolean internal,

            LocalDateTime sentAt
    ) {
    }
}
