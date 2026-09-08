package com.fnph.telepsychiatric.centre.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateReferralRequest",
        description = """
                Refer a centre patient for a telepsychiatry consultation.

                **A referral is per visit, not per patient.** A patient seen three times \
                has three referrals, each with its own presenting condition. That history \
                is the reason a consultation happens.

                **Not for emergencies.** The service excludes emergencies, severe \
                agitation, acute psychosis and immediate risk. Those go to physical or \
                emergency care, which is why `urgency` has no emergency option.
                """)
public record CreateReferralRequest(

        @NotBlank(message = "A centre patient is required")
        @Schema(description = "The centre-local patient this referral is for.",
                example = "01M1X5FF5ZR2M84M6088QR0FGE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String centrePatientPublicId,

        @NotBlank(message = "A referral reason is required")
        @Size(min = 20, max = 4000)
        @Schema(description = """
                Why this patient needs to be seen.

                The consulting doctor reads this before the session and it is the only \
                clinical context they have: the offline FNPH record is not retrieved for \
                a centre patient, even when the patient also holds an FNPH number.
                """,
                example = "Persistent low mood and poor sleep over four months, no improvement "
                        + "on current medication. Requesting psychiatric review.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String referralReason,

        @Size(max = 4000)
        @Schema(description = "The assessment carried out at the centre.")
        String assessment,

        @Size(max = 4000)
        @Schema(description = "How the patient presents now.")
        String currentCondition,

        @Size(max = 4000)
        @Schema(description = "What the patient is currently taking.",
                example = "Amitriptyline 25mg nightly since June 2026")
        String relevantMedicines,

        @Size(max = 4000)
        @Schema(description = "Any previous laboratory results held at the centre.")
        String previousResults,

        @Schema(description = "`SOON` asks for an earlier slot if one exists. It is not a "
                + "clinical priority claim and does not bypass the queue.",
                allowableValues = {"ROUTINE", "SOON"}, example = "ROUTINE")
        String urgency
) {
}
