package com.fnph.telepsychiatric.centre.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The clinical content of a draft or returned referral. The patient does not change. */
@Schema(name = "UpdateReferralRequest")
public record UpdateReferralRequest(
        @NotBlank(message = "A referral reason is required")
        @Size(min = 20, max = 4000)
        String referralReason,
        @Size(max = 4000) String assessment,
        @Size(max = 4000) String currentCondition,
        @Size(max = 4000) String relevantMedicines,
        @Size(max = 4000) String previousResults,
        @Schema(allowableValues = {"ROUTINE", "SOON"}) String urgency
) {
}
