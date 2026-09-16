package cn.timebank.dataexchange.application.validation;

import cn.timebank.dataexchange.application.validation.ImportRuleSet.ColumnSpec;
import cn.timebank.dataexchange.application.validation.ImportRuleSet.RuleSpec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code import-rules.yaml} once at startup (plan §2.1, decision D-1).
 *
 * <p>Parsing happens eagerly in the constructor so a malformed rule file fails
 * the application at boot rather than at the moment someone uploads a roster.
 * That matches how this codebase treats the rest of its configuration —
 * {@code TIMEBANK_ALLOWED_ORIGINS} and {@code TIMEBANK_FILE_TOKEN_SECRET} both
 * refuse to start rather than degrade — and a rule file that does not parse is
 * exactly as broken as a missing secret.</p>
 *
 * <p>SnakeYAML is already on the classpath (Spring Boot parses
 * {@code application.yml} with it), so this adds no dependency.</p>
 */
@Component
public class YamlRuleSetSource implements RuleSetSource {

    private static final String LOCATION = "import-rules.yaml";

    private final Map<String, ImportRuleSet> byType;

    public YamlRuleSetSource() {
        this(LOCATION);
    }

    YamlRuleSetSource(String location) {
        this.byType = load(location);
    }

    @Override
    public ImportRuleSet forType(String importType) {
        ImportRuleSet ruleSet = byType.get(importType);
        if (ruleSet == null) {
            throw new IllegalArgumentException("No import rule set for type '" + importType + "'");
        }
        return ruleSet;
    }

    @Override
    public Set<String> supportedTypes() {
        return byType.keySet();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ImportRuleSet> load(String location) {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read import rules from " + location, e);
        }
        if (root == null || !(root.get("types") instanceof Map)) {
            throw new IllegalStateException(location + " has no 'types' mapping");
        }

        Map<String, Object> types = (Map<String, Object>) root.get("types");
        Map<String, ImportRuleSet> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : types.entrySet()) {
            Map<String, Object> body = (Map<String, Object>) entry.getValue();
            List<Map<String, Object>> rawColumns = (List<Map<String, Object>>) body.get("columns");
            if (rawColumns == null || rawColumns.isEmpty()) {
                throw new IllegalStateException("Import type '" + entry.getKey() + "' declares no columns");
            }
            List<ColumnSpec> columns = new ArrayList<>();
            for (Map<String, Object> rawColumn : rawColumns) {
                columns.add(parseColumn(entry.getKey(), rawColumn));
            }
            parsed.put(entry.getKey(), new ImportRuleSet(
                    entry.getKey(), List.copyOf(columns),
                    asInt(body.get("maxRows"), 10_000),
                    asLong(body.get("maxBytes"), 10L * 1024 * 1024)));
        }
        return Map.copyOf(parsed);
    }

    @SuppressWarnings("unchecked")
    private static ColumnSpec parseColumn(String type, Map<String, Object> raw) {
        String name = (String) raw.get("name");
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("A column of import type '" + type + "' has no name");
        }
        List<RuleSpec> rules = new ArrayList<>();
        List<Map<String, Object>> rawRules = (List<Map<String, Object>>) raw.getOrDefault("rules", List.of());
        for (Map<String, Object> rawRule : rawRules) {
            String typeName = String.valueOf(rawRule.get("type"));
            RuleType ruleType;
            try {
                ruleType = RuleType.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                // A typo here would otherwise silently drop a validation rule,
                // which is the worst possible failure for this file.
                throw new IllegalStateException(
                        "Unknown rule type '" + typeName + "' on column '" + name + "' of import type '" + type + "'");
            }
            String code = (String) rawRule.get("code");
            if (code == null || code.isBlank()) {
                throw new IllegalStateException("Rule " + typeName + " on '" + name + "' has no error code");
            }
            rules.add(new RuleSpec(ruleType, code,
                    stringMap(rawRule.get("params")), stringMap(rawRule.get("messages"))));
        }
        return new ColumnSpec(name, String.valueOf(raw.getOrDefault("sample", "")),
                Boolean.TRUE.equals(raw.get("optional")), List.copyOf(rules));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        ((Map<Object, Object>) raw).forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return Map.copyOf(out);
    }

    private static int asInt(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }

    private static long asLong(Object raw, long fallback) {
        return raw instanceof Number n ? n.longValue() : fallback;
    }
}
