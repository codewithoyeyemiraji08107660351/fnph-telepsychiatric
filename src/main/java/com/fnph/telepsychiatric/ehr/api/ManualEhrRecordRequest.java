package com.fnph.telepsychiatric.ehr.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ManualEhrRecordRequest(
        @NotBlank @Size(max = 50) String ehrNumber,
        @NotBlank @Size(max = 150) String fullName,
        @NotNull LocalDate dateOfBirth,
        @Size(max = 30) String phoneNumber,
        @Email @Size(max = 150) String email,
        @Size(max = 100) String clinic,
        @Size(max = 50) String patientStatus,
        @NotNull Boolean active,
        @NotBlank @Size(min = 5, max = 500) String reason,
        Long version
) {}
