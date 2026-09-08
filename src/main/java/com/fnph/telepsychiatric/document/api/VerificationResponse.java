package com.fnph.telepsychiatric.document.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DocumentVerification",
        description = """
                The result of scanning a QR code.

                **Deliberately thin.** It confirms the document was issued by FNPH Kaduna \
                and says whether it is still valid. It carries no patient name, no \
                clinician name, no medication and no diagnosis.

                The person scanning could be anyone who found a piece of paper, and the \
                document in their hand already shows what they legitimately need. This \
                endpoint only confirms it was not forged.
                """)
public record VerificationResponse(

        @Schema(description = """
                `VALID` means genuine and in date. `EXPIRED` means genuine but past its \
                validity: **a saved copy will always report this**, which is the point. \
                `REVOKED` means withdrawn by the hospital and must not be acted on. \
                `SUPERSEDED` means a corrected version replaced it. `NOT_FOUND` means no \
                such document, which is also what a guessed code returns.
                """,
                allowableValues = {"VALID", "EXPIRED", "REVOKED", "SUPERSEDED", "NOT_FOUND"},
                example = "VALID")
        String status,

        @Schema(description = "Check this matches the number printed on the document.",
                example = "RX-2610-K7M4NPQR", nullable = true)
        String issueNumber,

        @Schema(example = "PRESCRIPTION", nullable = true)
        String documentType,

        @Schema(example = "2026-10-07", nullable = true)
        String issuedOn,

        @Schema(example = "2026-10-14", nullable = true)
        String expiresOn
) {
}
