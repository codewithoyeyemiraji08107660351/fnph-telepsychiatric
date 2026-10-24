package com.fnph.telepsychiatric.payment.export;

import com.fnph.telepsychiatric.payment.export.FinanceWorkbook.Column;
import com.fnph.telepsychiatric.payment.export.FinanceWorkbook.Kind;
import com.fnph.telepsychiatric.payment.export.FinanceWorkbook.SummaryLine;
import com.fnph.telepsychiatric.payment.export.FinanceWorkbook.Table;
import org.dhatim.fastexcel.BorderSide;
import org.dhatim.fastexcel.BorderStyle;
import org.dhatim.fastexcel.PaperSize;
import org.dhatim.fastexcel.StyleSetter;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes a {@link FinanceWorkbook} as an .xlsx file.
 *
 * <h2>Numbers stay numbers</h2>
 *
 * Amounts, counts and dates are written as typed cells with a display format,
 * never as preformatted text. Finance will sum, filter and pivot this file, and
 * "₦12,500.00" typed as text cannot be added up.
 *
 * <h2>Text is never a formula</h2>
 *
 * Every string is written as a shared string, so a failure reason or a
 * description that happens to begin with "=" is shown as typed rather than
 * evaluated when the file is opened.
 */
public final class FinanceWorkbookWriter {

    static final String MONEY_FORMAT = "#,##0.00;[Red]-#,##0.00";
    static final String COUNT_FORMAT = "#,##0";
    static final String DATETIME_FORMAT = "dd mmm yyyy hh:mm";
    static final String DATE_FORMAT = "dd mmm yyyy";

    private static final String NAVY = "1F3A5F";
    private static final String WHITE = "FFFFFF";
    private static final String BAND = "E8EEF5";
    private static final String MUTED = "5B6675";

    /** Rows above the column headers on a data sheet: heading, note, provenance, gap. */
    private static final int HEADER_ROW = 4;

    private FinanceWorkbookWriter() {
    }

    public static void write(FinanceWorkbook book, OutputStream out) throws IOException {
        Path raw = Files.createTempFile("fnph-finance-", ".xlsx");
        try {
            try (OutputStream rawOut = Files.newOutputStream(raw)) {
                writeWorkbook(book, rawOut);
            }
            repack(raw, out);
        } finally {
            Files.deleteIfExists(raw);
        }
    }

    /**
     * Rewrites the archive as a plain zip.
     *
     * fastexcel streams every entry as Zip64 with a trailing data descriptor
     * and a zero DOS date. Excel accepts that. LibreOffice 24.2 and later
     * refuse it as a damaged file, and so does java.util.zip.ZipInputStream.
     * Finance staff on LibreOffice, or anyone importing into Google Sheets,
     * would get "file could not be loaded". The rewrite costs one pass over a
     * file of a few hundred kilobytes and opens everywhere.
     *
     * [Content_Types].xml goes first, which some strict readers expect.
     */
    static void repack(Path source, OutputStream out) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .sorted(Comparator.comparing((ZipEntry e) -> !"[Content_Types].xml".equals(e.getName()))
                            .thenComparing(ZipEntry::getName))
                    .toList();
            ZipOutputStream repacked = new ZipOutputStream(new FilterOutputStream(out) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    // The caller owns the stream.
                    flush();
                }
            });
            long now = System.currentTimeMillis();
            for (ZipEntry original : entries) {
                ZipEntry entry = new ZipEntry(original.getName());
                entry.setTime(now);
                repacked.putNextEntry(entry);
                try (InputStream in = zip.getInputStream(original)) {
                    in.transferTo(repacked);
                }
                repacked.closeEntry();
            }
            repacked.close();
        }
    }

    private static void writeWorkbook(FinanceWorkbook book, OutputStream out) throws IOException {
        Workbook workbook = new Workbook(out, "FNPH Telepsychiatry", "1.0");
        workbook.properties()
                .setTitle(book.title())
                .setCompany("Federal Neuro-Psychiatric Hospital, Kaduna");

        writeSummary(workbook.newWorksheet("Summary"), book);
        for (Table table : book.tables()) {
            writeTable(workbook.newWorksheet(table.sheetName()), book, table);
        }
        workbook.finish();
    }

    // -----------------------------------------------------------------

    private static void writeSummary(Worksheet ws, FinanceWorkbook book) {
        printSetup(ws);
        ws.width(0, 46);
        ws.width(1, 20);
        ws.width(2, 80);

        int r = 0;
        ws.value(r, 0, book.title());
        ws.style(r, 0).bold().fontSize(14).fontColor(NAVY).set();
        r++;
        for (String line : book.provenance()) {
            ws.value(r, 0, line);
            ws.style(r, 0).fontColor(MUTED).set();
            r++;
        }
        r++;

        for (SummaryLine line : book.summary()) {
            if (line.isHeading()) {
                r++;
                ws.value(r, 0, line.label());
                for (int c = 0; c < 3; c++) {
                    ws.style(r, c).bold().fillColor(BAND).fontColor(NAVY).set();
                }
                r++;
                continue;
            }
            ws.value(r, 0, line.label());
            put(ws, r, 1, line.value(), line.kind(), false, false);
            if (line.note() != null) {
                ws.value(r, 2, line.note());
                ws.style(r, 2).fontColor(MUTED).wrapText(true).verticalAlignment("top").set();
            }
            r++;
        }
        ws.freezePane(0, 1);
    }

    private static void writeTable(Worksheet ws, FinanceWorkbook book, Table table) {
        List<Column> columns = table.columns();
        printSetup(ws);
        ws.repeatRows(HEADER_ROW);
        for (int c = 0; c < columns.size(); c++) {
            ws.width(c, columns.get(c).width());
        }

        ws.value(0, 0, table.heading());
        ws.style(0, 0).bold().fontSize(14).fontColor(NAVY).set();
        if (table.note() != null) {
            ws.value(1, 0, table.note());
            ws.style(1, 0).fontColor(MUTED).set();
        }
        ws.value(2, 0, String.join("   |   ", book.provenance()));
        ws.style(2, 0).fontColor(MUTED).italic().set();

        for (int c = 0; c < columns.size(); c++) {
            ws.value(HEADER_ROW, c, columns.get(c).header());
            ws.style(HEADER_ROW, c)
                    .bold().fontColor(WHITE).fillColor(NAVY)
                    .wrapText(true).verticalAlignment("center")
                    .horizontalAlignment(isNumeric(columns.get(c).kind()) ? "right" : "left")
                    .set();
        }
        ws.rowHeight(HEADER_ROW, 32);
        ws.freezePane(0, HEADER_ROW + 1);

        List<List<Object>> rows = table.rows();
        if (rows.isEmpty()) {
            ws.value(HEADER_ROW + 1, 0, table.emptyMessage());
            ws.style(HEADER_ROW + 1, 0).italic().fontColor(MUTED).set();
            return;
        }

        BigDecimal[] totals = new BigDecimal[columns.size()];
        int r = HEADER_ROW + 1;
        for (List<Object> row : rows) {
            for (int c = 0; c < columns.size(); c++) {
                Object value = c < row.size() ? row.get(c) : null;
                Column column = columns.get(c);
                put(ws, r, c, value, column.kind(), false, false);
                if (column.total() && value instanceof Number n) {
                    totals[c] = (totals[c] == null ? BigDecimal.ZERO : totals[c]).add(decimal(n));
                }
            }
            r++;
        }
        int lastDataRow = r - 1;
        ws.setAutoFilter(HEADER_ROW, 0, lastDataRow, columns.size() - 1);

        if (columns.stream().anyMatch(Column::total)) {
            for (int c = 0; c < columns.size(); c++) {
                Column column = columns.get(c);
                Object value = c == 0 ? "Total" : column.total()
                        ? (totals[c] == null ? BigDecimal.ZERO : totals[c]) : null;
                put(ws, r, c, value, c == 0 ? Kind.TEXT : column.kind(), true, true);
            }
        }
    }

    /**
     * A4 landscape, one page wide, as many pages tall as it needs. Finance
     * prints these for sign-off, and a sheet that spills its right-hand
     * columns onto separate pages cannot be read on paper.
     */
    private static void printSetup(Worksheet ws) {
        ws.paperSize(PaperSize.A4_PAPER);
        ws.pageOrientation("landscape");
        ws.fitToWidth((short) 1);
        ws.fitToHeight((short) 0);
    }

    // -----------------------------------------------------------------

    /**
     * Writes one cell with its whole style in a single call. fastexcel replaces
     * a cell's style on every set(), so building it in pieces would keep only
     * the last piece.
     */
    private static void put(Worksheet ws, int r, int c, Object value, Kind kind,
                            boolean bold, boolean topBorder) {
        String format = null;
        if (value == null) {
            // Nothing to write; a styled empty cell still carries the total
            // row's border so the row reads as one line.
        } else if (kind == Kind.MONEY || kind == Kind.COUNT) {
            if (value instanceof Number n) {
                ws.value(r, c, n);
                format = kind == Kind.MONEY ? MONEY_FORMAT : COUNT_FORMAT;
            } else {
                ws.value(r, c, value.toString());
            }
        } else if (kind == Kind.DATETIME && value instanceof LocalDateTime t) {
            ws.value(r, c, t);
            format = DATETIME_FORMAT;
        } else if (kind == Kind.DATE && value instanceof LocalDate d) {
            ws.value(r, c, d);
            format = DATE_FORMAT;
        } else if (value instanceof Boolean b) {
            ws.value(r, c, b ? "Yes" : "No");
        } else {
            ws.value(r, c, value.toString());
        }

        if (format == null && !bold && !topBorder) {
            return;
        }
        StyleSetter style = ws.style(r, c);
        if (format != null) {
            style = style.format(format);
        }
        if (bold) {
            style = style.bold();
        }
        if (topBorder) {
            style = style.borderStyle(BorderSide.TOP, BorderStyle.MEDIUM).borderColor(BorderSide.TOP, NAVY);
        }
        style.set();
    }

    private static boolean isNumeric(Kind kind) {
        return kind == Kind.MONEY || kind == Kind.COUNT;
    }

    private static BigDecimal decimal(Number n) {
        return n instanceof BigDecimal b ? b : new BigDecimal(n.toString());
    }
}
