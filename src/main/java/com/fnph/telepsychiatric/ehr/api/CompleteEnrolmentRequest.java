package com.fnph.telepsychiatric.ehr.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "CompleteEnrolmentRequest",
        description = "Steps two and three: confirm the code and choose a password.")
public record CompleteEnrolmentRequest(

        @NotBlank(message = "The verification reference is required")
        @Schema(description = "From the lookup response.", requiredMode = Schema.RequiredMode.REQUIRED)
        String verificationPublicId,

        @NotBlank(message = "Enter the code")
        @Pattern(regexp = "^[0-9]{6}$", message = "The code is six digits")
        @Schema(description = "The six-digit code sent to your phone.", example = "482913",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String code,

        @NotBlank(message = "Choose a password")
        @Size(min = 12, max = 200, message = "Use at least 12 characters")
        @Schema(description = "At least 12 characters. A short phrase you can remember is "
                + "fine and is stronger than a short complicated one.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String password
) {
}
