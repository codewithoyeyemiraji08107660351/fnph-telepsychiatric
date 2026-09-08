package com.fnph.telepsychiatric.clinical.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(name = "ReleaseBundle",
        description = """
                Everything one consultation produced, released together or not at all.

                A partial release would send a patient a prescription while their \
                investigation request was still under review, and they would act on half \
                their care plan without knowing.
                """)
public record ReleaseBundleResponse(

        @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(description = """
                `INCOMPLETE` means something is outstanding. `READY` means every component \
                is done or recorded as not needed. `BLOCKED` means the coordinator is \
                holding it deliberately.
                """,
                allowableValues = {"INCOMPLETE", "READY", "RELEASED", "BLOCKED"},
                example = "READY")
        String status,

        @Schema(description = "Why it is held, when blocked.", nullable = true)
        String blockedReason,

        @Schema(description = "Every expected component and where it stands.")
        List<ComponentState> components,

        @Schema(nullable = true)
        String releasedBy,

        @Schema(nullable = true)
        LocalDateTime releasedAt
) {

    @Schema(name = "BundleComponent", description = "One expected part of the bundle.")
    public record ComponentState(

            @Schema(allowableValues = {"CLINICAL_NOTE", "PRESCRIPTION", "INVESTIGATION", "FOLLOW_UP"},
                    example = "PRESCRIPTION")
            String componentType,

            @Schema(description = "Whether it is done.", example = "true")
            boolean complete,

            @Schema(description = """
                    The doctor decided none was needed. Distinct from not-done-yet: \
                    without the distinction a consultation that legitimately produced no \
                    investigation request would sit blocked forever.
                    """,
                    example = "false")
            boolean notRequired,

            @Schema(description = "Why none was needed.", nullable = true)
            String notRequiredReason,

            @Schema(description = "True when this is holding the release.", example = "false")
            boolean outstanding
    ) {
    }
}
