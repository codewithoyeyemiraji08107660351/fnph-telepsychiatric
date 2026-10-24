package com.fnph.telepsychiatric.payment.api;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.handler.ErrorResponse;
import com.fnph.telepsychiatric.payment.export.FinanceExportService;
import com.fnph.telepsychiatric.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/finance/reports")
@RequiredArgsConstructor
@Tag(name = "Finance — Reporting")
public class FinanceExportController {

    static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final FinanceExportService exportService;
    private final AuditService auditService;

    /*
     * No `produces` on the mapping, deliberately. With it, a refused export
     * (bad dates, too many rows) would try to write its JSON error as an Excel
     * file, fail content negotiation, and reach the client as a bare 406
     * instead of the message telling Finance to shorten the period.
     */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).FINANCE_REPORT_READ)")
    @Operation(
            summary = "Download the Finance Excel report for a period",
            description = """
                    One .xlsx workbook covering the chosen days, West Africa Time, both ends
                    inclusive. Sheets: Summary, Payments, Daily totals, Failed and reversed,
                    Exceptions, Centre wallets, Wallet ledger.

                    Amounts and dates are typed cells, so the file can be summed, filtered and
                    pivoted as it is. Patients appear by EHR number only.

                    An end date after today is read as today. At most 366 days per export, and
                    a period with more than 10,000 payments is refused with the count rather
                    than cut short.

                    Every export is written to the audit trail with its period.

                    **Requires** `finance_report.read`, held by Finance and the Central
                    Administrator.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Workbook returned as an attachment.",
                    content = @Content(mediaType =
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
            @ApiResponse(responseCode = "400",
                    description = "Dates missing, reversed, in the future, longer than 366 days, "
                            + "or the period holds more than 10,000 payments.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<byte[]> export(
            @Parameter(description = "First day, yyyy-MM-dd, WAT.", example = "2026-09-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day, yyyy-MM-dd, WAT, inclusive.", example = "2026-09-30")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        FinanceExportService.Export export =
                exportService.export(from, to, CurrentUser.usernameOrSystem());

        // Outside the read-only export transaction, and after the file exists:
        // the trail records exports that happened, not ones that were refused.
        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.FINANCE_REPORT_EXPORTED)
                .entityType("FinanceReport")
                .details("Excel export %s to %s (WAT), %d payments"
                        .formatted(export.from(), export.to(), export.paymentCount()))
                .outcome("SUCCESS")
                .build());

        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .contentLength(export.content().length)
                .body(export.content());
    }
}
