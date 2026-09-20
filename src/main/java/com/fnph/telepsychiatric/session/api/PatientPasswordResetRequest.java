package com.fnph.telepsychiatric.session.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PatientPasswordResetRequest(
        @NotBlank(message = "Enter your EHR number")
        @Size(max = 50, message = "EHR number is too long")
        String ehrNumber
) {
}
