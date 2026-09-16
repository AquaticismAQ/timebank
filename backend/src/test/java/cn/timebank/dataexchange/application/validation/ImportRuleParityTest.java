package cn.timebank.dataexchange.application.validation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The safety net for plan §3.6: the migration deleted two hand-written
 * validators, and the only thing making that safe is that the rule set
 * reproduces their error codes exactly.
 *
 * <p>These codes are a published contract. They go into the downloadable error
 * CSV and into {@code GET /imports/{id}/errors}, so a client or a school's own
 * spreadsheet may key off them. Renaming one is a breaking change, and the
 * point of this test is that it cannot happen by accident — if you change a
 * code in {@code import-rules.yaml}, this test fails and you have to decide
 * deliberately.</p>
 *
 * <p>The expected sets below were read off the deleted validators
 * (git history: {@code StudentImportRowValidator} and
 * {@code HistoricalImportRowValidator} before this change), not off the new
 * YAML — copying them from the YAML would make the test tautological.</p>
 */
class ImportRuleParityTest {

    /** Every code the old StudentImportRowValidator could return. */
    private static final Set<String> LEGACY_STUDENT_CODES = Set.of(
            "PARSE_ERROR", "REQUIRED", "DUPLICATE_IN_FILE", "STUDENT_NUMBER_TAKEN",
            "UNKNOWN_GRADE", "UNKNOWN_CLASS", "CLASS_GRADE_MISMATCH", "UNKNOWN_ACADEMIC_YEAR",
            "INVALID_YEAR", "INVALID_DATE");

    /** Every code the old HistoricalImportRowValidator could return. */
    private static final Set<String> LEGACY_HISTORICAL_CODES = Set.of(
            "PARSE_ERROR", "REQUIRED", "STUDENT_NOT_FOUND", "STUDENT_INACTIVE",
            "INVALID_DATE", "INVALID_DURATION", "NO_RULE_IN_EFFECT");

    private final RuleSetSource source = new YamlRuleSetSource();

    private Set<String> codesDeclaredFor(String type) {
        return source.forType(type).columns().stream()
                .flatMap(c -> c.rules().stream())
                .flatMap(r -> java.util.stream.Stream.of(r.code(), r.param("statusCode")))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    @Test
    void studentRulesStillEmitEveryLegacyCode() {
        Set<String> declared = codesDeclaredFor("students");
        // PARSE_ERROR is raised by the engine/reader rather than declared on a
        // column, so it is excluded from the declared-set comparison.
        Set<String> expected = LEGACY_STUDENT_CODES.stream()
                .filter(c -> !c.equals("PARSE_ERROR")).collect(Collectors.toSet());
        assertThat(declared).containsAll(expected);
    }

    @Test
    void historicalRulesStillEmitEveryLegacyCode() {
        Set<String> declared = codesDeclaredFor("serviceRecordsHistorical");
        // PARSE_ERROR comes from the reader; NO_RULE_IN_EFFECT is raised by the
        // validator after rule resolution, which is a domain lookup rather than
        // a cell rule and deliberately stayed in Java.
        Set<String> expected = LEGACY_HISTORICAL_CODES.stream()
                .filter(c -> !Set.of("PARSE_ERROR", "NO_RULE_IN_EFFECT").contains(c))
                .collect(Collectors.toSet());
        assertThat(declared).containsAll(expected);
    }

    @Test
    void studentColumnsMatchThePostV26Shape() {
        // Seven columns: V26 dropped email/guardianName/guardianContact.
        assertThat(source.forType("students").columnNames()).containsExactly(
                "studentNumber", "displayName", "gradeId", "classId",
                "enrollmentAcademicYearId", "graduationYear", "joinedAt");
    }

    @Test
    void historicalColumnsAreUnchanged() {
        assertThat(source.forType("serviceRecordsHistorical").columnNames()).containsExactly(
                "studentNumber", "serviceDate", "durationMinutes", "description");
    }

    @Test
    void everyTextColumnCarriesTheUnsafeContentRule() {
        // Plan §3.4: the security rules apply by default, not opt-in. A new
        // column added without one would silently accept formula injection.
        List<String> numericOrDateColumns = List.of("graduationYear", "joinedAt", "serviceDate", "durationMinutes");
        for (String type : source.supportedTypes()) {
            source.forType(type).columns().stream()
                    .filter(c -> !numericOrDateColumns.contains(c.name()))
                    .forEach(c -> assertThat(c.rules())
                            .as("column '%s' of '%s' must reject unsafe content", c.name(), type)
                            .anyMatch(r -> r.type() == RuleType.UNSAFE_CONTENT));
        }
    }

    @Test
    void everyRuleCarriesBothLanguages() {
        // Decision D-4: the report returns a resolved message as well as a code,
        // and this system is bilingual everywhere else.
        for (String type : source.supportedTypes()) {
            source.forType(type).columns().forEach(column -> column.rules().forEach(rule ->
                    assertThat(rule.messages().keySet())
                            .as("rule %s on '%s' must have en and zh-CN messages", rule.code(), column.name())
                            .contains("en", "zh-CN")));
        }
    }

    @Test
    void supportedTypesAreExactlyTheTwoImportsTheApiOffers() {
        assertThat(source.supportedTypes()).containsExactlyInAnyOrder("students", "serviceRecordsHistorical");
    }
}
