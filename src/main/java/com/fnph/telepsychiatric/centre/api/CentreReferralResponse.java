package com.fnph.telepsychiatric.centre.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "CentreReferral", description = "A referral from this centre.")
public record CentreReferralResponse(

        @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(description = "Quote this to FNPH.", example = "REF-K7M4N-PQR8T")
        String reference,

        @Schema(description = "The centre-local patient identifier.", example = "BGW-0042")
        String centrePatientId,

        @Schema(example = "Ngozi Okonkwo")
        String patientName,

        @Schema(description = """
                `DRAFT` is not yet visible to FNPH. `SUBMITTED` is waiting for an \
                appointment request. `SCHEDULED` has one. `RETURNED` means FNPH asked for \
                more information.
                """,
                allowableValues = {"DRAFT", "SUBMITTED", "SCHEDULED", "RETURNED", "COMPLETED",
                        "WITHDRAWN"},
                example = "SUBMITTED")
        String status,

        @Schema(allowableValues = {"ROUTINE", "SOON"}, example = "ROUTINE")
        String urgency,

        @Schema(description = "Consent recorded for this referral, not carried over from an "
                + "earlier one.", nullable = true)
        LocalDateTime consentAcceptedAt,

        @Schema(description = "Who at the centre witnessed the patient consenting.",
                nullable = true)
        String consentWitnessedBy,

        @Schema(nullable = true)
        LocalDateTime submittedAt,

        LocalDateTime createdAt
) {
}
