package com.fnph.telepsychiatric.consultation.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "TerminateConsultationRequest",
        description = "End a session early. Clinician only.")
public record TerminateConsultationRequest(

        @NotNull(message = "A reason is required")
        @Schema(description = """
                Why the session is ending early.

                `EMERGENCY` returns the approved escalation instruction in the response, \
                so a clinician handling an emergency over a video call is not reaching for \
                a policy document to find a number.
                """,
                allowableValues = {"FAILED_IDENTITY_VERIFICATION", "UNACCEPTABLE_PRIVACY",
                        "PERSISTENT_DISRUPTION", "ABUSE", "EMERGENCY",
                        "ACUTE_CLINICAL_UNSUITABILITY", "UNSAFE_CONNECTIVITY", "OTHER"},
                example = "UNSAFE_CONNECTIVITY", requiredMode = Schema.RequiredMode.REQUIRED)
        String reason,

        @Size(max = 2000)
        @Schema(description = "What happened, in the clinician's words.",
                example = "Video and audio dropped four times in six minutes; the patient could not be heard.")
        String note,

        @NotBlank(message = "Record what was done to keep the patient safe")
        @Size(min = 10, max = 2000)
        @Schema(description = """
                What was done to keep the patient safe.

                Mandatory, and the request is refused without it. A session ended for \
                abuse, an emergency or acute unsuitability leaves a patient somewhere, and \
                what happened next is the part that matters clinically and the part a \
                later review will ask about.
                """,
                example = "Called the patient on the number on file, confirmed they were safe, "
                        + "and rebooked for Thursday.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String safetyAction
) {
}
