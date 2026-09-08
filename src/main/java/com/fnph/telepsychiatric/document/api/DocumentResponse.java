package com.fnph.telepsychiatric.document.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "IssuedDocument", description = "A released clinical document.")
public record DocumentResponse(

        @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(description = """
                Quote this on the phone. The format avoids characters that are ambiguous \
                when read aloud: no I, L, O, U, zero or one.
                """,
                example = "RX-2610-K7M4NPQR")
        String issueNumber,

        @Schema(allowableValues = {"PRESCRIPTION", "INVESTIGATION_REQUEST", "CLINICAL_SUMMARY",
                "FOLLOW_UP_RECOMMENDATION"}, example = "PRESCRIPTION")
        String documentType,

        @Schema(description = """
                `ACTIVE` is usable. `EXPIRED` is past its validity. `REVOKED` was withdrawn \
                deliberately. `SUPERSEDED` was replaced by a corrected version.
                """,
                allowableValues = {"ACTIVE", "EXPIRED", "REVOKED", "SUPERSEDED"}, example = "ACTIVE")
        String status,

        LocalDateTime issuedAt,

        @Schema(description = "After this it can still be read but not downloaded, and a saved "
                + "copy verifies as expired.")
        LocalDateTime expiresAt,

        @Schema(description = "Downloads used.", example = "0")
        int downloadCount,

        @Schema(description = "Downloads allowed, frozen at issue so a later configuration "
                + "change cannot reduce an allowance already given.",
                example = "1")
        int maxDownloads,

        @Schema(description = """
                The allowance is used. **The document is still readable on screen** until \
                it expires; only the file download is refused. Show it rather than hiding \
                it.
                """,
                example = "false")
        boolean viewOnly,

        @Schema(description = "Scan or open this to verify the document. Render it as a QR "
                + "code on the printed version.",
                example = "https://telepsychiatry.fnphkaduna.gov.ng/verify/x7K2...")
        String verificationUrl,

        @Schema(description = "Why it was withdrawn.", nullable = true)
        String revokedReason
) {
}
