package com.fnph.telepsychiatric.ehr.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(name = "EnrolmentLookupRequest",
        description = """
                Step one of patient enrolment: find the existing hospital record.
                Date of birth or the last four phone digits can be supplied as an
                additional match where the deployment requires it.
                """)
public record EnrolmentLookupRequest(

        @NotBlank(message = "Hospital number is required")
        @Size(max = 50, message = "Hospital number is too long")
        String ehrNumber

) {
}