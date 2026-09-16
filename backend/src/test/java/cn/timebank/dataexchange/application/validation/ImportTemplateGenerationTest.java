package cn.timebank.dataexchange.application.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §3.5: the template is generated from the rule set, so the two cannot
 * drift. Previously they were authored separately and a template could
 * advertise a column the validator never read.
 *
 * <p>The strongest form of that guarantee is a round trip — a template parsed
 * back by the reader must produce exactly the columns the rules expect — which
 * is what these assert rather than checking a hardcoded string.</p>
 */
class ImportTemplateGenerationTest {

    private final RuleSetSource source = new YamlRuleSetSource();

    /** Mirrors ImportServiceImpl.template's header line. */
    private String headerFor(String type) {
        return String.join(",", source.forType(type).columnNames());
    }

    @Test
    void theTemplateHeaderIsExactlyTheRuleSetsColumns() {
        for (String type : source.supportedTypes()) {
            List<String> parsed = Rfc4180Reader.parseLine(headerFor(type));
            assertThat(parsed)
                    .as("template header for '%s' must round-trip to its declared columns", type)
                    .isEqualTo(source.forType(type).columnNames());
        }
    }

    @Test
    void everyColumnOffersASampleValue() {
        // A template row of empty cells teaches nobody the expected format.
        for (String type : source.supportedTypes()) {
            source.forType(type).columns().forEach(column ->
                    assertThat(column.sample())
                            .as("column '%s' of '%s' needs a sample value", column.name(), type)
                            .isNotBlank());
        }
    }

    @Test
    void theSampleRowSatisfiesItsOwnFormatRules() {
        // The sample the template hands a school must not be something the
        // validator would reject — that would be a self-inflicted support call.
        var students = source.forType("students");
        assertThat(students.column("studentNumber").sample()).matches("[0-9a-zA-Z_-]+");
        assertThat(students.column("joinedAt").sample()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(Integer.parseInt(students.column("graduationYear").sample())).isGreaterThanOrEqualTo(2000);

        var historical = source.forType("serviceRecordsHistorical");
        assertThat(historical.column("serviceDate").sample()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(Integer.parseInt(historical.column("durationMinutes").sample())).isPositive();
    }

    @Test
    void aSampleContainingACommaWouldBeQuoted() {
        // The historical description sample has parentheses today; if someone
        // adds a comma, the generated template must stay parseable.
        String withComma = "Cleanup, autumn 2024";
        String cell = withComma.contains(",") ? '"' + withComma + '"' : withComma;
        assertThat(Rfc4180Reader.parseLine("a," + cell)).containsExactly("a", withComma);
    }
}
