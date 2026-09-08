package com.fnph.telepsychiatric.ehr.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(name = "EhrImport", description = "An uploaded snapshot of the offline FNPH EHR.")
public record EhrImportResponse(

        @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String publicId,

        @Schema(example = "fnph_patients_2026_09_01.csv")
        String fileName,

        @Schema(description = """
                UPLOADED and VALIDATING are transient. VALIDATED means the file parsed \
                cleanly and can be activated. REJECTED means nothing was loaded; read \
                `validationReport`. ACTIVE is the snapshot enrolment matches against. \
                SUPERSEDED means a newer one replaced it.
                """,
                allowableValues = {"UPLOADED", "VALIDATING", "VALIDATED", "REJECTED", "ACTIVE", "SUPERSEDED"},
                example = "VALIDATED")
        String status,

        @Schema(description = "The date the hospital extracted the file.", example = "2026-09-01")
        LocalDate sourceAsAt,

        @Schema(description = "How stale the extract is, in days. Shown wherever it is relied on.",
                example = "6")
        long ageInDays,

        @Schema(example = "4412")
        int rowCount,

        @Schema(example = "4412")
        int validRowCount,

        @Schema(description = "Rows that failed. A non-zero value means nothing was loaded.",
                example = "0")
        int rejectedRowCount,

        @Schema(description = "Line-numbered errors when rejected, or a confirmation when valid.",
                nullable = true)
        String validationReport,

        @Schema(description = """
                Active accounts whose stored name or phone differs from this snapshot.

                They are **flagged, not updated**. Silently rebinding an active account \
                to changed contact details would let a wrong or malicious row redirect a \
                patient's verification codes. HIM reviews each one.
                """,
                example = "3")
        int driftDetectedCount,

        @Schema(nullable = true)
        String uploadedBy,

        LocalDateTime uploadedAt,

        @Schema(nullable = true)
        LocalDateTime activatedAt,

        @Schema(nullable = true)
        String activatedBy
) {
}
