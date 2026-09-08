package com.fnph.telepsychiatric.ehr.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(name = "EnrolmentLookupRequest",
        description = """
                Step one of patient enrolment: prove you hold the record.

                The EHR number alone is never enough. Supply **either** the date of \
                birth **or** the last four digits of the phone number the hospital has \
                on file. Either one plus the number is accepted; both are not required, \
                because a phone number recorded years ago would push legitimate patients \
                into the exception queue.
                """)
public record EnrolmentLookupRequest(

        @NotBlank(message = "Enter your EHR number")
        @Size(max = 50)
        @Schema(description = "The number printed on your hospital card.",
                example = "FNPH/2019/04417", requiredMode = Schema.RequiredMode.REQUIRED)
        String ehrNumber,

        @Past(message = "The date of birth must be in the past")
        @Schema(description = "Your date of birth, exactly as the hospital recorded it. "
                + "Supply this or `phoneLastFour`.",
                example = "1988-04-12", nullable = true)
        LocalDate dateOfBirth,

        @Pattern(regexp = "^[0-9]{4}$", message = "Enter exactly four digits")
        @Schema(description = "The last four digits of your phone number. "
                + "Supply this or `dateOfBirth`.",
                example = "5678", nullable = true)
        String phoneLastFour
) {
}
