package com.fnph.telepsychiatric.ehr.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

@Schema(name = "VerificationHelpRequest",
        description = """
                Ask the hospital to verify you by hand when automatic matching fails.

                Needed because the record list is a snapshot. A patient registered last \
                week will not appear in an extract taken last month, and refusing them \
                with no route forward would send them back for a physical visit over an \
                administrative gap.

                Submitting this creates no account and grants nothing.
                """)
public record VerificationHelpRequest(

        @NotBlank(message = "Enter your EHR number")
        @Size(max = 50)
        @Schema(example = "FNPH/2026/09112", requiredMode = Schema.RequiredMode.REQUIRED)
        String ehrNumber,

        @NotBlank(message = "Enter your full name")
        @Size(max = 150)
        @Schema(example = "Ngozi Okonkwo", requiredMode = Schema.RequiredMode.REQUIRED)
        String fullName,

        @NotNull(message = "Enter your date of birth")
        @Past
        @Schema(example = "1988-04-12", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDate dateOfBirth,

        @NotBlank(message = "Enter a phone number we can reach you on")
        @Size(max = 20)
        @Schema(example = "+2348012345678", requiredMode = Schema.RequiredMode.REQUIRED)
        String phoneNumber,

        @Email
        @Size(max = 100)
        @Schema(nullable = true)
        String email,

        @Schema(description = "How you prefer to be contacted.",
                allowableValues = {"SMS", "EMAIL", "CALL"}, example = "SMS", nullable = true)
        String preferredContact,

        @Size(max = 2000)
        @Schema(description = "Anything that helps identify you: the clinic you attend, "
                + "roughly when you last visited, the doctor you saw.",
                example = "Adult outpatient clinic, last seen around July 2026", nullable = true)
        String supportingNote
) {
}
