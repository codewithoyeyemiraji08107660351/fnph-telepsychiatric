package com.fnph.telepsychiatric.payment.export;

import java.util.List;

/**
 * What goes into a Finance Excel export, independent of how it is written.
 *
 * Kept free of Spring and JPA so the layout can be produced and checked from
 * plain values. Every timestamp in here is already hospital time (WAT); the
 * writer does no conversion.
 *
 * @param title      workbook title, also the first line of the summary sheet
 * @param provenance period, generation time and author, repeated at the top of
 *                   every sheet so a page printed on its own still says what it is
 * @param summary    the key figures, in order
 * @param tables     one sheet each, in order after the summary
 */
public record FinanceWorkbook(String title,
                              List<String> provenance,
                              List<SummaryLine> summary,
                              List<Table> tables) {

    public enum Kind { TEXT, MONEY, COUNT, DATETIME, DATE }

    /**
     * A column on a data sheet.
     *
     * @param total whether the sheet's total row sums this column. Only set on
     *              columns where a sum means something: adding up the charged
     *              amount across failed and successful payments does not.
     */
    public record Column(String header, Kind kind, double width, boolean total) {

        public static Column text(String header, double width) {
            return new Column(header, Kind.TEXT, width, false);
        }

        public static Column money(String header) {
            return new Column(header, Kind.MONEY, 18, false);
        }

        public static Column count(String header) {
            return new Column(header, Kind.COUNT, 14, false);
        }

        public static Column dateTime(String header) {
            return new Column(header, Kind.DATETIME, 19, false);
        }

        public static Column date(String header) {
            return new Column(header, Kind.DATE, 14, false);
        }

        public Column totalled() {
            return new Column(header, kind, width, true);
        }
    }

    /**
     * One line on the summary sheet. A line with no kind is a section heading.
     */
    public record SummaryLine(String label, Object value, Kind kind, String note) {

        public static SummaryLine heading(String label) {
            return new SummaryLine(label, null, null, null);
        }

        public static SummaryLine money(String label, Object value, String note) {
            return new SummaryLine(label, value, Kind.MONEY, note);
        }

        public static SummaryLine count(String label, Object value, String note) {
            return new SummaryLine(label, value, Kind.COUNT, note);
        }

        public static SummaryLine text(String label, Object value, String note) {
            return new SummaryLine(label, value, Kind.TEXT, note);
        }

        public boolean isHeading() {
            return kind == null;
        }
    }

    /**
     * A data sheet.
     *
     * @param note         one line under the heading explaining what the rows are
     * @param emptyMessage shown in place of rows when there are none, so an
     *                     empty sheet reads as "nothing happened", not "broken"
     */
    public record Table(String sheetName,
                        String heading,
                        String note,
                        List<Column> columns,
                        List<List<Object>> rows,
                        String emptyMessage) {
    }
}
