package com.fnph.telepsychiatric.ehr.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "CompleteEnrolmentRequest",
        description = "Step two: choose a password for the matched patient record.")
public record CompleteEnrolmentRequest(

        @NotBlank(message = "The verification reference is required")
        @Schema(description = "From the lookup response.", requiredMode = Schema.RequiredMode.REQUIRED)
        String verificationPublicId,

        @NotBlank(message = "Choose a password")
        @Size(min = 8, max = 200, message = "Use at least 8 characters")
        @Schema(description = "A password chosen by the patient, at least 8 characters.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String password
) {
}
