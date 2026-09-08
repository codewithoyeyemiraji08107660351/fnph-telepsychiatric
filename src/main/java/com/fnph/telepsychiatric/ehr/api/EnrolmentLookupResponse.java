package com.fnph.telepsychiatric.ehr.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(name = "EnrolmentLookup",
        description = """
                Your record was matched and a verification code has been sent.

                Details are **masked**. The full date of birth and phone number are never \
                returned, and the code goes to the number the hospital holds rather than \
                to one you supply.
                """)
public record EnrolmentLookupResponse(

        @Schema(description = "Pass this back with the code to finish enrolling.",
                example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String verificationPublicId,

        @Schema(description = "The name on your hospital record. Check it is you.",
                example = "Ngozi Okonkwo")
        String fullName,

        @Schema(description = "Masked date of birth, year only.", example = "**/**/1988")
        String dateOfBirthMasked,

        @Schema(description = "Where the code was sent.", example = "*******5678")
        String phoneMasked,

        @Schema(description = "The clinic on your record.", example = "Adult Outpatient",
                nullable = true)
        String clinic,

        @Schema(description = "When the code expires (UTC).")
        LocalDateTime codeExpiresAt,

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
