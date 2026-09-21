package cn.timebank.dataexchange.application;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Every column convention in `Design/*&#47;13-DataExport.md` §2, in one place
 * (`11-DataImportExportOptimization.md` §2.8).
 *
 * <p>These conventions are cross-cutting and there are seven export types. Left
 * to each writer they drift: one renders an absent value as the literal
 * {@code null}, another forgets that coins are stored in hundredths, a third
 * prints {@code 0.8571} where the reader expects {@code 85.71%}. Issue 107 was
 * exactly that drift, found in a delivered file. So the counter, the empty cell,
 * the coin scale, the percentage, the dates and the injection guard all live
 * here, and a query's job is to supply values — never to format them.</p>
 *
 * <p><b>{@code String.valueOf} is banned in this class and in callers building
 * rows.</b> It is the direct cause of a cell reading {@code null}: on a null
 * Integer it cheerfully produces the four characters rather than an empty cell.
 * Use {@link #cell(Object)}.</p>
 *
 * <p>Row numbering is emitted by the writer rather than by any query, so no
 * query can forget it and no two can disagree about whether it is 0- or
 * 1-based (§2.3: it is 1-based, and it is the first column).</p>
 */
class ExportCsvWriter implements AutoCloseable {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Coins are stored in hundredths — the "minimum accounting unit" the domain
     * conventions require so nothing is ever a float. One hour credited is
     * {@code 100} in the column and must read {@code 1}.
     */
    private static final BigDecimal COIN_SCALE = new BigDecimal(100);

    private final BufferedWriter writer;
    private int rowNumber;

    ExportCsvWriter(Path file) throws IOException {
        this.writer = Files.newBufferedWriter(file);
    }

    /**
     * Writes the header row. {@code No.} is prepended here, so callers list only
     * their own columns and cannot omit the counter.
     */
    void header(String... columns) throws IOException {
        writeRaw(prepend("No.", List.of(columns)));
    }

    /** Writes one data row, prefixed with its 1-based row number. */
    void row(Object... values) throws IOException {
        rowNumber++;
        List<String> cells = new java.util.ArrayList<>(values.length);
        for (Object value : values) {
            cells.add(cell(value));
        }
        writeRaw(prepend(String.valueOf(rowNumber), cells));
    }

    int rowsWritten() {
        return rowNumber;
    }

    /**
     * One value as a cell.
     *
     * <p>{@code null} becomes an <b>empty cell</b> (§2.4), never the text
     * "null" and never a placeholder like "N/A": an empty cell is the
     * spreadsheet's own way of saying absent, and any text in it is a value
     * someone will later filter or sum by mistake.</p>
     */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDate date) {
            return DATE.format(date);
        }
        if (value instanceof LocalDateTime dateTime) {
            return DATE_TIME.format(dateTime);
        }
        if (value instanceof Instant instant) {
            return DATE_TIME.format(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
        }
        return value.toString();
    }

    /**
     * Centi-coins to coins: {@code 100} → {@code 1}, {@code 150} → {@code 1.5}.
     *
     * <p>Trailing zeros stripped so a whole number reads as one. Null stays null
     * so an uncredited row keeps its empty cell — which is the whole point of
     * `Credited Coins` on a service-records export, where summing the column is
     * only meaningful once the reader has filtered to approved rows.</p>
     */
    static String coins(Integer centiCoins) {
        if (centiCoins == null) {
            return "";
        }
        BigDecimal coins = new BigDecimal(centiCoins).divide(COIN_SCALE, 2, RoundingMode.HALF_UP);
        return coins.stripTrailingZeros().toPlainString();
    }

    /** Minutes to hours, same treatment: {@code 90} → {@code 1.5}. */
    static String hours(Integer minutes) {
        if (minutes == null) {
            return "";
        }
        BigDecimal h = new BigDecimal(minutes).divide(new BigDecimal(60), 2, RoundingMode.HALF_UP);
        return h.stripTrailingZeros().toPlainString();
    }

    /**
     * A rate as a percentage to two decimals with the sign — {@code 85.71%}.
     *
     * <p>§2.5: the suffix is part of the value. Without it a column of
     * {@code 0.8571} invites being read as a count, and a reader who multiplies
     * by 100 in a spreadsheet has to know which convention this file chose.</p>
     */
    static String percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            // No denominator is not 0% — it is "no basis to state a rate". An
            // explicit 0.00% here would assert something false about a student
            // or reviewer with nothing to measure.
            return "";
        }
        BigDecimal rate = new BigDecimal(numerator)
                .multiply(new BigDecimal(100))
                .divide(new BigDecimal(denominator), 2, RoundingMode.HALF_UP);
        return rate.toPlainString() + "%";
    }

    private List<String> prepend(String first, List<String> rest) {
        List<String> all = new java.util.ArrayList<>(rest.size() + 1);
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private void writeRaw(List<String> cells) throws IOException {
        writer.write(csvLine(cells.toArray(new String[0])));
        writer.newLine();
    }

    /**
     * Joins one row's already-formatted cells.
     *
     * <p>Package-private and static so the escaping is unit-testable without
     * running a job — the property under test (a hostile student name cannot
     * become a live formula) is worth asserting directly rather than inferring
     * from a file.</p>
     */
    static String csvLine(String... fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            String field = fields[i] == null ? "" : fields[i];
            line.append(quote(neutralizeFormulaInjection(field)));
        }
        return line.toString();
    }

    /**
     * RFC 4180 quoting — moved here unchanged (§2.7: "already holds in the
     * implementation and must not be weakened").
     */
    private static String quote(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * The set of leading characters a spreadsheet may treat as the start of a
     * formula. Includes tab and carriage return, which Excel skips over before
     * deciding — so a cell beginning "\t=cmd" is still a formula to it.
     */
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    /**
     * Stops a spreadsheet treating a cell as a formula.
     *
     * <p>A student display name beginning {@code =} would otherwise be evaluated
     * by Excel when the file is opened, which is a code-execution path that
     * starts with someone typing their own name. A leading apostrophe forces the
     * cell to text and is not displayed.</p>
     *
     * <p>Moved here from {@code ExportJobRunner} <b>unchanged</b>, character set
     * and prefix both: §2.7 records this as something that "already holds in the
     * implementation and must not be weakened", and a tidier-looking rewrite is
     * exactly how the tab and carriage-return triggers would get dropped.</p>
     */
    static String neutralizeFormulaInjection(String value) {
        if (!value.isEmpty() && FORMULA_TRIGGERS.indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
