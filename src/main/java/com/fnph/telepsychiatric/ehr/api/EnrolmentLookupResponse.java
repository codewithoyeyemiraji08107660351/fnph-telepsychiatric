package com.fnph.telepsychiatric.ehr.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(name = "EnrolmentLookup",
        description = """
                The existing hospital record was matched. Use the short-lived setup
                reference to choose a password in step two.
                """)
public record EnrolmentLookupResponse(

        @Schema(description = "Pass this back with the chosen password to finish enrolling.",
                example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String verificationPublicId,

        @Schema(description = "The matched hospital EHR number.", example = "204815")
        String ehrNumber,

        @Schema(description = "The name on your hospital record. Check it is you.",
                example = "Ngozi Okonkwo")
        String fullName,

        @Schema(description = "Masked date of birth, year only.", example = "**/**/1988")
        String dateOfBirthMasked,

        @Schema(description = "The clinic on your record.", example = "Adult Outpatient",
                nullable = true)
        String clinic,

        @Schema(description = "When this setup reference expires (UTC).")
        LocalDateTime setupExpiresAt,

        @Schema(description = """
                The date the hospital extracted the record list this was matched against.

                Shown because it is a snapshot, not a live link. If your details changed \
                after this date they will not be reflected yet.
                """,
                example = "2026-09-01")
        LocalDate recordsAsAt,

        @Schema(description = "How many days old that extract is.", example = "6")
        long recordsAgeInDays
) {
}
