package cn.timebank.dataexchange.application.validation;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal RFC 4180 CSV reader (plan §3.1, gap G-1).
 *
 * <p>Replaces {@code line.split(",", -1)}, which could not read back what
 * {@code ExportJobRunner} writes: the exporter quotes any field containing a
 * comma or a quote, and the splitter treated the quoted comma as a column
 * separator. An export → edit → re-import round trip therefore shifted every
 * column after a name like {@code "Lee, Alex"} — silently, with no error,
 * because the shifted row usually still parsed.</p>
 *
 * <p>Written by hand rather than pulling in a CSV library: the grammar is
 * small and fully specified, and this module's entire job is handling hostile
 * files, so fewer third-party parsers in that path is a feature. It handles
 * quoted fields, doubled quotes ({@code ""}) as an escaped quote, and
 * newlines embedded inside quoted fields.</p>
 *
 * <p>A record may span multiple physical lines, so callers must read through
 * {@link #readRecord(BufferedReader)} rather than looping on
 * {@code readLine()} themselves.</p>
 */
public final class Rfc4180Reader {

    private Rfc4180Reader() {
    }

    /** Raised for input that is not well-formed CSV; callers turn this into a row-level {@code PARSE_ERROR} rather than letting it escape. */
    public static final class MalformedCsvException extends RuntimeException {
        public MalformedCsvException(String message) {
            super(message);
        }
    }

    /**
     * Reads one logical record, consuming as many physical lines as its quoted
     * fields require.
     *
     * @return the record's fields, or {@code null} at end of input
     */
    public static List<String> readRecord(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;

        while (true) {
            if (i >= line.length()) {
                if (!inQuotes) {
                    fields.add(field.toString());
                    return fields;
                }
                // Unterminated quote: the field continues on the next physical
                // line, with the line break preserved as part of the value.
                String next = reader.readLine();
                if (next == null) {
                    throw new MalformedCsvException("Unterminated quoted field at end of input");
                }
                field.append('\n');
                line = next;
                i = 0;
                continue;
            }

            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    boolean doubled = i + 1 < line.length() && line.charAt(i + 1) == '"';
                    if (doubled) {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
            } else if (c == '"') {
                if (!field.isEmpty()) {
                    throw new MalformedCsvException("Unexpected quote in the middle of an unquoted field");
                }
                inQuotes = true;
                i++;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                i++;
            } else {
                field.append(c);
                i++;
            }
        }
    }

    /** Convenience for tests and single-line parsing; rejects input whose quoting spans lines. */
    public static List<String> parseLine(String line) {
        try {
            List<String> fields = readRecord(new BufferedReader(new java.io.StringReader(line)));
            return fields == null ? List.of() : fields;
        } catch (IOException e) {
            throw new MalformedCsvException("Could not read line: " + e.getMessage());
        }
    }
}
