package com.fnph.telepsychiatric.clinical.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Schema(
        name = "ReleaseBundle",
        description = """
                Everything one consultation produced, released together or not at all.

                The bundle contains the administrative component state together with
                read-only clinical content so the Hub Coordinator can see exactly what
                is about to be released to the patient.
                """
)
public record ReleaseBundleResponse(

        @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(
                description = """
                        `INCOMPLETE` means something is outstanding. `READY` means every
                        component is done or recorded as not needed. `BLOCKED` means the
                        coordinator is holding it deliberately.
                        """,
                allowableValues = {"INCOMPLETE", "READY", "RELEASED", "BLOCKED"},
                example = "READY"
        )
        String status,

        @Schema(description = "Why it is held, when blocked.", nullable = true)
        String blockedReason,

        @Schema(description = "Every expected component and where it stands.")
        List<ComponentState> components,

        @Schema(
                description = """
                        Read-only clinical contents produced by this consultation.
                        This is informational for the Hub Coordinator and cannot be
                        edited from the release workflow.
                        """
        )
        ClinicalContent clinicalContent,

        @Schema(nullable = true)
        String releasedBy,

        @Schema(nullable = true)
        LocalDateTime releasedAt,

        @Schema(
                description = """
                        The hospital appointment this bundle belongs to. Keys the hub
                        oversight endpoints. Null for centre bundles.
                        """,
                nullable = true
        )
        String appointmentPublicId
) {

    @Schema(
            name = "BundleComponent",
            description = "One expected part of the bundle."
    )
    public record ComponentState(

            @Schema(
                    allowableValues = {
                            "CLINICAL_NOTE",
                            "PRESCRIPTION",
                            "INVESTIGATION",
                            "FOLLOW_UP"
                    },
                    example = "PRESCRIPTION"
            )
            String componentType,

            @Schema(description = "Whether it is done.", example = "true")
            boolean complete,

            @Schema(
                    description = """
                            The doctor decided none was needed. Distinct from not-done-yet:
                            without the distinction a consultation that legitimately produced
                            no investigation request would sit blocked forever.
                            """,
                    example = "false"
            )
            boolean notRequired,

            @Schema(description = "Why none was needed.", nullable = true)
            String notRequiredReason,

            @Schema(
                    description = "True when this is holding the release.",
                    example = "false"
            )
            boolean outstanding
    ) {
    }

    @Schema(
            name = "ClinicalContent",
            description = "Read-only clinical material produced by the consultation."
    )
    public record ClinicalContent(

            @Schema(
                    description = "The current non-superseded consultation note.",
                    nullable = true
            )
            ClinicalNoteContent clinicalNote,

            @Schema(description = "Prescriptions belonging to this release bundle.")
            List<PrescriptionContent> prescriptions,

            @Schema(description = "Investigation requests belonging to this release bundle.")
            List<InvestigationContent> investigations,

            @Schema(description = "Follow-up recommendations belonging to this release bundle.")
            List<FollowUpContent> followUps
    ) {
    }

    @Schema(name = "ReleaseClinicalNote")
    public record ClinicalNoteContent(

            String clinicalNote,

            Integer version,

            boolean authoritative,

            boolean signed,

            LocalDateTime signedAt,

            String signedBy,

            String followUpRecommendation,

            String followUpTimeline
    ) {
    }

    @Schema(name = "ReleasePrescription")
    public record PrescriptionContent(

            String issueNumber,

            String status,

            boolean notRequired,

            String notRequiredReason,

            LocalDate issueDate,

            LocalDate expiryDate,

            Integer validityDays,

            String clinicalInformation,

            List<PrescriptionItemContent> items
    ) {
    }

    @Schema(name = "ReleasePrescriptionItem")
    public record PrescriptionItemContent(

            String medication,

            String strength,

            String frequency,

            String duration,

            String instructions,

            Integer sequence
    ) {
    }

    @Schema(name = "ReleaseInvestigation")
    public record InvestigationContent(

            String issueNumber,

            String status,

            boolean notRequired,

            String notRequiredReason,

            String clinicalInformation,

            LocalDate issueDate,

            LocalDate expiryDate,

            Integer validityDays,

            List<InvestigationItemContent> items
    ) {
    }

    @Schema(name = "ReleaseInvestigationItem")
    public record InvestigationItemContent(

            String panelName,

            String panelCode,

            String notes,

            Integer sequence
    ) {
    }

    @Schema(name = "ReleaseFollowUp")
    public record FollowUpContent(

            String recommendation,

            String reviewInterval,

            String expectedTimeframe,

            LocalDate preferredDate,

            LocalTime preferredTime,

            String consultationMode,

            String status,

            LocalDate scheduledDate,

            LocalDate completedDate,

            String notes
    ) {
    }
}