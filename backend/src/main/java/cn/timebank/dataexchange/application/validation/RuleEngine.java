package cn.timebank.dataexchange.application.validation;

import cn.timebank.dataexchange.application.validation.ImportRuleSet.ColumnSpec;
import cn.timebank.dataexchange.application.validation.ImportRuleSet.RuleSpec;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Interprets an {@link ImportRuleSet} against one row at a time (plan §3.2).
 *
 * <p>Evaluation is ordered and short-circuits at the first failure, per column
 * and then across columns, so a bad row yields one clear error rather than a
 * cascade of consequences of the same mistake.</p>
 *
 * <p>File-scope state (in-file uniqueness) lives in a {@link Run}, which the
 * caller creates once per validate or commit pass. That mirrors the
 * {@code seenInFile} set the hand-written validators used, and keeps the
 * engine itself stateless and therefore safe to share as a singleton.</p>
 */
@Component
public class RuleEngine {

    /**
     * Characters that make displayed text differ from stored text. U+202E and
     * friends re-order rendering, so a name can appear to read one way in the
     * admin UI while holding something else — see plan gap G-3.
     */
    private static final Set<Character> BIDI_OVERRIDES = Set.of(
            '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069');

    /**
     * Leading characters a spreadsheet treats as the start of a formula
     * (CWE-1236). Phase 1 neutralises these when writing an export; rejecting
     * them on the way in means hostile content never reaches storage in the
     * first place, so a future exporter that forgets to escape cannot resurrect
     * the problem.
     */
    private static final Set<Character> FORMULA_PREFIXES = Set.of('=', '+', '-', '@', '\t', '\r');

    private final RuleLookups lookups;

    public RuleEngine(RuleLookups lookups) {
        this.lookups = lookups;
    }

    /** Per-pass mutable state: everything a FILE-scope rule needs to remember across rows. */
    public static final class Run {
        private final Map<String, Set<String>> seenByColumn = new HashMap<>();

        Set<String> seen(String column) {
            return seenByColumn.computeIfAbsent(column, c -> new HashSet<>());
        }
    }

    public Run newRun() {
        return new Run();
    }

    /**
     * Validates one already-parsed row.
     *
     * @param ruleSet the rules for this import type
     * @param values  the row's cells, positionally aligned with {@code ruleSet.columns()}
     * @param run     per-pass state for file-scope rules
     * @param lang    language code for the resolved message, e.g. {@code en}
     */
    public RowValidation validate(ImportRuleSet ruleSet, List<String> values, Run run, String lang) {
        List<ColumnSpec> columns = ruleSet.columns();
        if (values.size() < columns.size()) {
            return new RowValidation.Invalid("row", "PARSE_ERROR",
                    "Expected " + columns.size() + " columns (" + String.join(", ", ruleSet.columnNames())
                            + ") but found " + values.size());
        }

        Map<String, String> cells = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            cells.put(columns.get(i).name(), values.get(i).trim());
        }

        // Pass 1 — FIELD rules, per column, in declaration order.
        for (ColumnSpec column : columns) {
            String value = cells.get(column.name());
            if (column.optional() && value.isBlank()) {
                continue;
            }
            for (RuleSpec rule : column.rules()) {
                if (rule.type().scope() != RuleType.Scope.FIELD) {
                    continue;
                }
                String failure = checkField(rule, value);
                if (failure != null) {
                    return invalid(column.name(), rule, lang, failure, value);
                }
            }
        }

        // Pass 2 — ROW rules, which may read any cell.
        for (ColumnSpec column : columns) {
            for (RuleSpec rule : column.rules()) {
                if (rule.type() != RuleType.REFERENCE_CONSISTENT) {
                    continue;
                }
                String value = cells.get(column.name());
                String otherColumn = rule.param("matches");
                String expected = cells.get(otherColumn);
                String actual = lookups.attributeOf(rule.param("lookup"), value).orElse(null);
                if (actual != null && !actual.equals(expected)) {
                    return invalid(column.name(), rule, lang, null, value);
                }
            }
        }

        // Pass 3 — FILE rules: in-file uniqueness, then database checks. Ordered
        // last so a row that is malformed on its own face never costs a query.
        for (ColumnSpec column : columns) {
            String value = cells.get(column.name());
            if (column.optional() && value.isBlank()) {
                continue;
            }
            for (RuleSpec rule : column.rules()) {
                switch (rule.type()) {
                    case UNIQUE_IN_FILE -> {
                        // Case-folded deliberately. This rule models a database
                        // uniqueness constraint, and the column's collation
                        // (utf8mb4_0900_ai_ci) is case-insensitive — so a file
                        // holding both HKTZ4HKV and hktz4hkv would pass a
                        // case-sensitive check here and then fail at INSERT
                        // partway through commit, which is the half-loaded state
                        // two-phase validation exists to prevent. Locale.ROOT
                        // avoids the Turkish dotless-i trap.
                        if (!run.seen(column.name()).add(value.toUpperCase(Locale.ROOT))) {
                            return invalid(column.name(), rule, lang, null, value);
                        }
                    }
                    case UNIQUE_IN_DB -> {
                        if (lookups.exists(rule.param("lookup"), value)) {
                            return invalid(column.name(), rule, lang, null, value);
                        }
                    }
                    case MUST_EXIST_IN_DB -> {
                        var student = lookups.student(value);
                        if (student.isEmpty()) {
                            return invalid(column.name(), rule, lang, null, value);
                        }
                        String requiredStatus = rule.param("status");
                        if (requiredStatus != null && !requiredStatus.equals(student.get().status())) {
                            return new RowValidation.Invalid(column.name(), rule.param("statusCode"),
                                    render(rule.messages().get(statusMessageKey(lang)), value, null));
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        return new RowValidation.Valid(cells);
    }

    /** @return null when the rule passes, otherwise a detail fragment for the message */
    private String checkField(RuleSpec rule, String value) {
        return switch (rule.type()) {
            case REQUIRED -> value.isBlank() ? "" : null;
            case MAX_LENGTH -> value.length() > rule.intParam("max", Integer.MAX_VALUE) ? "" : null;
            case REGEX -> Pattern.compile(rule.param("pattern")).matcher(value).matches() ? null : "";
            case UNSAFE_CONTENT -> checkUnsafe(value);
            case INT_RANGE -> {
                int parsed;
                try {
                    parsed = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    yield "";
                }
                String min = rule.param("min");
                String max = rule.param("max");
                if (min != null && parsed < Integer.parseInt(min)) yield "";
                if (max != null && parsed > Integer.parseInt(max)) yield "";
                yield null;
            }
            case DATE_FORMAT -> {
                try {
                    LocalDate.parse(value);
                    yield null;
                } catch (Exception e) {
                    yield "";
                }
            }
            case REFERENCE_EXISTS -> lookups.exists(rule.param("lookup"), value) ? null : "";
            default -> null;
        };
    }

    private String checkUnsafe(String value) {
        if (value.isEmpty()) {
            return null;
        }
        if (FORMULA_PREFIXES.contains(value.charAt(0))) {
            return "";
        }
        for (char c : value.toCharArray()) {
            if (BIDI_OVERRIDES.contains(c)) {
                return "";
            }
            // C0 (excluding tab/CR/LF, handled above as formula prefixes or
            // stripped by the reader) and C1 control characters.
            boolean c0 = c < 0x20 && c != '\t' && c != '\r' && c != '\n';
            boolean c1 = c >= 0x7F && c <= 0x9F;
            if (c0 || c1) {
                return "";
            }
        }
        return null;
    }

    private RowValidation.Invalid invalid(String field, RuleSpec rule, String lang, String detail, String value) {
        return new RowValidation.Invalid(field, rule.code(), render(messageFor(rule, lang), value, detail));
    }

    private String messageFor(RuleSpec rule, String lang) {
        Map<String, String> messages = rule.messages();
        String exact = messages.get(lang);
        if (exact != null) {
            return exact;
        }
        // Fall back to English, then to the code itself, rather than throwing:
        // a missing translation must never turn a rejected row into a 500.
        String english = messages.get("en");
        return english != null ? english : rule.code();
    }

    private String statusMessageKey(String lang) {
        return "status." + lang;
    }

    private String render(String template, String value, String detail) {
        if (template == null) {
            return "";
        }
        String out = template.replace("{value}", value == null ? "" : value);
        return detail == null ? out : out.replace("{detail}", detail);
    }
}
