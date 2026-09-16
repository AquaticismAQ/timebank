package cn.timebank.dataexchange.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Docs/optimization/08-SecurityPrivacyHardening.md §3.5 (finding W-6), CWE-1236.
 *
 * <p>A student name is user-controlled and ends up in an exported CSV that an
 * administrator opens in a spreadsheet. Without neutralisation a name beginning
 * {@code =} is executed as a formula on open.</p>
 */
class ExportCsvInjectionTest {

    @ParameterizedTest
    @ValueSource(strings = {"=", "+", "-", "@", "\t", "\r"})
    void fieldsStartingWithAFormulaTriggerAreNeutralised(String trigger) {
        String hostile = trigger + "HYPERLINK(\"http://attacker.example\",\"click\")";

        String line = ExportJobRunner.csvLine(hostile);

        assertThat(ExportJobRunner.neutralizeFormulaInjection(hostile)).startsWith("'");
        assertThat(line).doesNotStartWith(trigger);
    }

    /**
     * The regression this guards: RFC 4180 quoting was already applied and is not
     * a defence. The comma forces the field to be quoted, and a reader that
     * assumed quoting was enough would see {@code "=cmd..."} and call it safe —
     * but the spreadsheet strips the envelope quotes before evaluating the cell.
     * The apostrophe must sit inside them.
     */
    @Test
    void quotingAloneIsNotTreatedAsSufficient() {
        String hostile = "=cmd|'/c calc'!A1,injected";

        String line = ExportJobRunner.csvLine(hostile);

        assertThat(line).startsWith("\"'=");
    }

    @Test
    void ordinaryFieldsAreUnchanged() {
        assertThat(ExportJobRunner.csvLine("Alex L.(李*红)", "a45b9def", "3"))
                .isEqualTo("Alex L.(李*红),a45b9def,3");
    }

    /** A negative sign on a real number must survive readably — it is still prefixed, but the value is intact. */
    @Test
    void negativeNumbersAreNeutralisedWithoutLosingTheValue() {
        assertThat(ExportJobRunner.csvLine("-50")).isEqualTo("'-50");
    }

    @Test
    void existingQuotingBehaviourIsPreserved() {
        assertThat(ExportJobRunner.csvLine("has,comma")).isEqualTo("\"has,comma\"");
        assertThat(ExportJobRunner.csvLine("has\"quote")).isEqualTo("\"has\"\"quote\"");
        assertThat(ExportJobRunner.csvLine((String) null)).isEmpty();
    }
}
