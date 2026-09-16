package cn.timebank.dataexchange.application.validation;

import java.util.List;
import java.util.Map;

/**
 * The declarative description of one import type (plan §3.2).
 *
 * <p>This is the whole contract between the YAML and the engine: what columns
 * a file must have, in what order the template presents them, what each cell
 * must satisfy, and the file-level caps. Nothing here is executable — the
 * engine interprets it, which is what keeps {@link RuleType} closed.</p>
 *
 * @param type        the import type, matching {@code Job.resourceType}
 * @param columns     declared columns, in template order
 * @param maxRows     reject the file outright beyond this many data rows
 * @param maxBytes    reject the file outright beyond this size
 */
public record ImportRuleSet(String type, List<ColumnSpec> columns, int maxRows, long maxBytes) {

    /**
     * One column and the rules bound to it.
     *
     * @param name     header name, also the {@code field} reported in errors
     * @param sample   the value the generated template shows as an example
     * @param optional when true, a blank cell skips every rule below
     * @param rules    evaluated in declaration order, short-circuiting on the first failure
     */
    public record ColumnSpec(String name, String sample, boolean optional, List<RuleSpec> rules) {
    }

    /**
     * One rule instance.
     *
     * @param type     which catalogue entry to apply
     * @param code     the stable machine code reported on failure — these are
     *                 pinned to the codes the hand-written validators produced,
     *                 so existing error reports and clients keep working
     * @param params   type-specific parameters (e.g. {@code pattern}, {@code max}, {@code lookup})
     * @param messages message template per language code, e.g. {@code en} / {@code zh-CN}
     */
    public record RuleSpec(RuleType type, String code, Map<String, String> params, Map<String, String> messages) {

        public String param(String key) {
            return params.get(key);
        }

        public int intParam(String key, int fallback) {
            String raw = params.get(key);
            return raw == null ? fallback : Integer.parseInt(raw);
        }
    }

    public ColumnSpec column(String name) {
        return columns.stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No column '" + name + "' in rule set '" + type + "'"));
    }

    public List<String> columnNames() {
        return columns.stream().map(ColumnSpec::name).toList();
    }
}
