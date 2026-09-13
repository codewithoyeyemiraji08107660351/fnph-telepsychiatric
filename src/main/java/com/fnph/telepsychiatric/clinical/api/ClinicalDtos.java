package com.fnph.telepsychiatric.clinical.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class ClinicalDtos {

    private ClinicalDtos() {
    }

    @Schema(name = "ClinicalNoteRequest", description = "Write or update the consultation note.")
    public record NoteRequest(
            @NotBlank @Size(max = 50_000)
            @Schema(description = "The clinical record of the consultation.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String clinicalNote
    ) {
    }

    @Schema(name = "SignNoteRequest",
            description = """
                    Sign the note. **Irreversible.**

                    A signed note cannot be edited. A later correction is an amendment, \
                    which creates a new version pointing at this one, because editing in \
                    place destroys what the clinician actually wrote at the time.
                    """)
    public record SignNoteRequest(
            @Size(max = 2000)
            @Schema(description = "What should happen next and when.",
                    example = "Review in six weeks. Continue current medication.")
            String followUpRecommendation,
            @Size(max = 100)
            @Schema(example = "6 weeks") String followUpTimeline
    ) {
    }

    @Schema(name = "AmendNoteRequest", description = "Supersede a signed note.")
    public record AmendNoteRequest(
            @NotBlank @Size(max = 50_000)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clinicalNote,
            @NotBlank @Size(min = 10, max = 500)
            @Schema(description = "Why. The previous version stays in the record and this "
                    + "is what explains the difference.",
                    example = "Corrected the recorded dose discussed in the session.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String amendmentReason
    ) {
    }

    @Schema(name = "PrescriptionItem", description = "One medication line.")
    public record PrescriptionItemRequest(
            @NotBlank @Size(max = 200)
            @Schema(example = "Sertraline", requiredMode = Schema.RequiredMode.REQUIRED)
            String medication,
            @NotBlank @Size(max = 100)
            @Schema(example = "50mg", requiredMode = Schema.RequiredMode.REQUIRED) String strength,
            @NotBlank @Size(max = 100)
            @Schema(example = "Once daily", requiredMode = Schema.RequiredMode.REQUIRED)
            String frequency,
            @NotBlank @Size(max = 100)
            @Schema(example = "28 days", requiredMode = Schema.RequiredMode.REQUIRED) String duration,
            @Size(max = 1000)
            @Schema(example = "Take in the morning with food.") String instructions
    ) {
    }

    @Schema(name = "IssuePrescriptionRequest",
            description = """
                    Issue a prescription. It goes to the assigned pharmacist for \
                    transcription and professional verification.

                    **It does not come back to you.** If the pharmacist raises a concern it \
                    reaches the Hub Coordinator and the multidisciplinary team. Changing it \
                    means issuing a new prescription that supersedes this one, which is the \
                    only version where the prescriber decided.
                    """)
    public record IssuePrescriptionRequest(
            @Size(max = 4000)
            @Schema(description = "Context the pharmacist needs. **Not the clinical note**, "
                    + "which pharmacy does not see.",
                    example = "Depressive episode, first-line treatment.")
            String clinicalInformation,
            @NotEmpty(message = "A prescription needs at least one item")
            @Valid List<PrescriptionItemRequest> items
    ) {
    }

    @Schema(name = "SupersedePrescriptionRequest",
            description = """
                    Replace a prescription with a corrected one.

                    The previous prescription is marked superseded and **any copy the \
                    patient already holds stops verifying as valid**, so it cannot be \
                    dispensed at a pharmacy counter.
                    """)
    public record SupersedePrescriptionRequest(
            @NotBlank @Size(min = 10, max = 500)
            @Schema(example = "Dose corrected following pharmacy review at the MDT.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String reason,
            @Size(max = 4000) String clinicalInformation,
            @NotEmpty @Valid List<PrescriptionItemRequest> items
    ) {
    }

    @Schema(name = "InvestigationPanel", description = "One requested panel.")
    public record InvestigationItemRequest(
            @NotBlank @Size(max = 200)
            @Schema(example = "Full blood count", requiredMode = Schema.RequiredMode.REQUIRED)
            String panelName,
            @Size(max = 50) @Schema(example = "FBC") String panelCode,
            @Size(max = 1000) String notes
    ) {
    }

    @Schema(name = "IssueInvestigationRequest",
            description = "Issue an investigation request. It goes to the assigned "
                    + "laboratory technician and does not return to you.")
    public record IssueInvestigationRequest(
            @Size(max = 4000)
            @Schema(description = "Context the technician needs. Not the clinical note.")
            String clinicalInformation,
            @NotEmpty(message = "A request needs at least one panel")
            @Valid List<InvestigationItemRequest> items
    ) {
    }

    @Schema(name = "FollowUpRequest", description = "What should happen after this consultation.")
    public record FollowUpRequest(
            @NotBlank @Size(max = 4000)
            @Schema(example = "Review in six weeks with the same clinician.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String recommendation,
            @Size(max = 100) @Schema(example = "6 weeks") String reviewInterval,
            @Schema(nullable = true) LocalDate preferredDate
    ) {
    }

    @Schema(name = "NotRequiredRequest",
            description = """
                    Record that a component is deliberately not needed.

                    **This is not the same as leaving it out.** Without it the release \
                    bundle waits forever for a document nobody intends to write, and the \
                    coordinator eventually releases it by guessing.
                    """)
    public record NotRequiredRequest(
            @NotBlank
            @Schema(allowableValues = {"PRESCRIPTION", "INVESTIGATION", "FOLLOW_UP"},
                    example = "INVESTIGATION", requiredMode = Schema.RequiredMode.REQUIRED)
            String component,
            @NotBlank @Size(min = 10, max = 500)
            @Schema(description = "Why none is needed. Without it this is indistinguishable "
                    + "from an unfinished consultation a year later.",
                    example = "No investigations indicated at this review.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String reason
    ) {
    }

    @Schema(name = "ClinicalNote", description = "One version of a consultation note.")
    public record NoteResponse(
            String publicId,
            @Schema(example = "1") int version,
            String clinicalNote,
            @Schema(description = "Null while unsigned. Once set the note is immutable.",
                    nullable = true)
            LocalDateTime signedAt,
            @Schema(nullable = true) String signedBy,
            @Schema(description = "Set when a later version replaced this one.", nullable = true)
            LocalDateTime supersededAt,
            @Schema(nullable = true) String amendmentReason,
            @Schema(nullable = true) String followUpRecommendation,
            @Schema(nullable = true) String followUpTimeline
    ) {
    }

    @Schema(name = "ClinicalDocument", description = "An issued prescription or request.")
    public record DocumentResponse(
            String publicId,
            @Schema(nullable = true) String issueNumber,
            @Schema(allowableValues = {"DRAFT", "NOT_REQUIRED", "PENDING_REVIEW", "REVIEWED",
                    "RELEASED", "EXPIRED", "SUPERSEDED", "REVOKED"}, example = "PENDING_REVIEW")
            String status,
            LocalDate issueDate,
            @Schema(nullable = true) LocalDate expiryDate,
            @Schema(description = "How many items or panels it carries.", example = "2")
            int itemCount,
            @Schema(description = "Set when a corrected version replaced this.", nullable = true)
            Long supersedesId
    ) {
    }
}
