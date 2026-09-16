package cn.timebank.dataexchange.application.validation;

import java.util.Map;

/**
 * The engine's verdict on one row (plan §3.2).
 *
 * <p>Carries both a stable machine {@code code} and a resolved human
 * {@code message} (decision D-4). The code is what clients and the error CSV
 * key off and must never change silently; the message is for a person reading
 * the report, and is resolved from the rule's own per-language templates.</p>
 */
public sealed interface RowValidation permits RowValidation.Valid, RowValidation.Invalid {

    /** Every declared column's value, keyed by header name, with rules satisfied. */
    record Valid(Map<String, String> cells) implements RowValidation {

        public String get(String column) {
            return cells.get(column);
        }

        /** Blank cells arrive as empty strings; optional columns want null instead. */
        public String getOrNull(String column) {
            String v = cells.get(column);
            return v == null || v.isBlank() ? null : v;
        }

        public int getInt(String column) {
            return Integer.parseInt(cells.get(column).trim());
        }

        public java.time.LocalDate getDate(String column) {
            return java.time.LocalDate.parse(cells.get(column).trim());
        }
    }

    record Invalid(String field, String code, String message) implements RowValidation {
    }
}
