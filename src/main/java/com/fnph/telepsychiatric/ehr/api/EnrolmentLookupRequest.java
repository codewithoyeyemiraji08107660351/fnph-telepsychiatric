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

        @NotBlank(message = "Enter your EHR number")
        @Size(max = 50)
        @Schema(description = "The number printed on your hospital card.",
                example = "FNPH/2019/04417", requiredMode = Schema.RequiredMode.REQUIRED)
        String ehrNumber,

        @Past(message = "The date of birth must be in the past")
        @Schema(description = "Your date of birth, exactly as the hospital recorded it. "
                + "This may be optional in the simplified enrolment flow.",
                example = "1988-04-12", nullable = true)
        LocalDate dateOfBirth,

        @Pattern(regexp = "^[0-9]{4}$", message = "Enter exactly four digits")
        @Schema(description = "The last four digits of your phone number. "
                + "This may be optional in the simplified enrolment flow.",
                example = "5678", nullable = true)
        String phoneLastFour
) {
}
