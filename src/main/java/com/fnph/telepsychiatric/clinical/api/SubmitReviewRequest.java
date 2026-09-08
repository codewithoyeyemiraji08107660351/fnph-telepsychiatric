package com.fnph.telepsychiatric.clinical.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "SubmitReviewRequest",
        description = """
                Submit a completed pharmacy or laboratory review.

                **There is no option to send this back to the doctor.** Reviews travel \
                forward to the Hub Coordinator. Raising a concern records it and still \
                moves forward; it does not reopen the document for editing, because a \
                prescription that can be changed by anyone other than the prescriber is \
                not a prescription.
                """)
public record SubmitReviewRequest(

        @NotNull(message = "An outcome is required")
        @Schema(description = """
                `VERIFIED` means transcribed and professionally verified with nothing to \
                raise. `QUERY_RAISED` records a concern for the multidisciplinary team.
                """,
                allowableValues = {"VERIFIED", "QUERY_RAISED"}, example = "VERIFIED",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String outcome,

        @Size(max = 2000)
        @Schema(description = "Your review notes.",
                example = "Transcribed to the offline EHR. Doses and interactions checked.")
        String notes,

        @Size(max = 2000)
        @Schema(description = """
                Required when the outcome is `QUERY_RAISED`. Describe the concern.

                It goes to the Hub Coordinator, so write it for someone who was not in \
                the consultation. If it needs a change, the doctor writes a new document \
                superseding this one.
                """,
                example = "Prescribed dose is above the usual maximum for this patient's weight. "
                        + "Requesting confirmation before dispensing.")
        String queryDetail
) {
}
