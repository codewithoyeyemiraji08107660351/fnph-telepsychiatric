package com.fnph.telepsychiatric.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** Request and response shapes for scheduling. */
public final class ScheduleDtos {

    private ScheduleDtos() {
    }

    @Schema(name = "PublishScheduleRequest",
            description = """
                    Open a consultation day.

                    Slot length is not a parameter. It comes from configuration per \
                    audience, so a change to the Centre duration cannot silently move FNPH \
                    appointments.

                    Capacity is not a parameter either. One slot is generated per period \
                    per active consultation room, which makes capacity a real thing rather \
                    than a number somebody typed. Taking a room out of service reduces \
                    tomorrow's capacity without anyone editing a schedule.
                    """)
    public record PublishScheduleRequest(

            @NotNull
            @Schema(description = "Which pathway this day serves. Separate schedules, so "
                    + "patient bookings cannot consume every centre slot on a busy morning.",
                    allowableValues = {"FNPH_PATIENT", "CENTRE"}, example = "FNPH_PATIENT",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String audience,

            @NotNull
            @Schema(example = "2026-11-02", requiredMode = Schema.RequiredMode.REQUIRED)
            LocalDate serviceDate,

            @NotNull
            @Schema(example = "09:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
            LocalTime windowStart,

            @NotNull
            @Schema(example = "16:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
            LocalTime windowEnd,

            @Schema(description = "False generates the slots but keeps the day invisible to "
                    + "patients, so it can be checked before it opens.",
                    example = "true")
            boolean publishImmediately
    ) {
    }

    @Schema(name = "SchedulePublication", description = "A published consultation day.")
    public record PublicationResponse(
            @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE") String publicId,
            @Schema(example = "FNPH_PATIENT") String audience,
            LocalDate serviceDate,
            LocalTime windowStart,
            LocalTime windowEnd,
            @Schema(example = "30") int slotMinutes,
            @Schema(description = "`DRAFT` is invisible to patients. `WITHDRAWN` takes no new "
                    + "bookings and leaves existing ones standing.",
                    allowableValues = {"DRAFT", "PUBLISHED", "WITHDRAWN"}, example = "PUBLISHED")
            String status,
            @Schema(description = "Total generated: periods multiplied by active rooms.",
                    example = "56")
            int slotsGenerated,
            @Schema(nullable = true) String publishedBy,
            @Schema(nullable = true) LocalDateTime publishedAt,
            @Schema(nullable = true) String withdrawReason
    ) {
    }

    @Schema(name = "AvailableTime",
            description = """
                    One bookable period.

                    Times are collapsed across rooms. Four rooms running at 09:00 is one \
                    entry with `remaining` of four, because asking a patient to choose \
                    between four identical times is asking a question they cannot answer.
                    """)
    public record AvailableTimeResponse(
            LocalDateTime startAt,
            LocalDateTime endAt,
            @Schema(description = "Pass this to hold the time. If it is taken between the "
                    + "list and the hold, another room at the same time is used "
                    + "automatically.")
            String slotPublicId,
            @Schema(description = "Rooms still free at this time.", example = "3")
            int remaining
    ) {
    }

    @Schema(name = "HoldSlotRequest", description = "Reserve a time while paying.")
    public record HoldSlotRequest(
            @NotBlank
            @Schema(description = "From the available times list.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String slotPublicId
    ) {
    }

    @Schema(name = "Appointment", description = "A booking and where it has got to.")
    public record AppointmentResponse(
            @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE") String publicId,
            @Schema(description = "Quote this when calling the hospital.",
                    example = "APT-K7M4NPQR8T")
            String reference,
            @Schema(description = """
                    `SLOT_HELD` is reserved while you pay and expires. `AWAITING_APPROVAL` \
                    is paid and with the hospital. `EXPIRED` means payment did not complete \
                    in time and the slot was released.
                    """,
                    allowableValues = {"SLOT_HELD", "EXPIRED", "AWAITING_APPROVAL", "APPROVED",
                            "REJECTED", "RESCHEDULED", "CANCELLED", "IN_PROGRESS", "COMPLETED",
                            "NO_SHOW"},
                    example = "SLOT_HELD")
            String status,
            LocalDateTime appointmentDate,
            LocalDateTime scheduledEndAt,
            @Schema(description = "When the reservation lapses. Null once paid.",
                    nullable = true)
            LocalDateTime heldUntil,
            @Schema(description = "Told to the patient. The doctor's name is not.",
                    example = "ROOM-01", nullable = true)
            String room,
            @Schema(description = "When the join control activates.", nullable = true)
            LocalDateTime joinWindowOpensAt,
            @Schema(nullable = true) String rejectedReason
    ) {
    }

    @Schema(name = "ApproveAppointmentRequest",
            description = """
                    Approve a paid request and assign the team.

                    The doctor and the room are required. The others are the \
                    multidisciplinary team and each one assigned receives a notice with the \
                    room, date and time.

                    Approval debits the patient wallet. A rejection never reaches this \
                    point, which is what leaves the balance intact.
                    """)
    public record ApproveAppointmentRequest(
            @NotBlank
            @Schema(description = "Checked against their availability for the whole slot.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String doctorPublicId,
            @NotBlank
            @Schema(description = "Must be an active room, and not one reserved for centre "
                    + "consultations.", requiredMode = Schema.RequiredMode.REQUIRED)
            String roomPublicId,
            @Schema(nullable = true) String nursePublicId,
            @Schema(nullable = true) String pharmacistPublicId,
            @Schema(nullable = true) String laboratoryTechnicianPublicId,
            @Schema(nullable = true) String himOfficerPublicId,
            @Size(max = 500)
            @Schema(description = "Recorded on the approval.", nullable = true)
            String notes
    ) {
    }

    @Schema(name = "AppointmentHistory", description = "Every status change, oldest first.")
    public record HistoryResponse(
            @Schema(nullable = true) String fromStatus,
            String toStatus,
            @Schema(nullable = true) String changedBy,
            LocalDateTime changedAt,
            @Schema(nullable = true) String reason
    ) {
    }

    @Schema(name = "ApprovalQueue", description = "Paid requests waiting on a decision.")
    public record QueueResponse(
            long total,
            List<AppointmentResponse> appointments
    ) {
    }
}
