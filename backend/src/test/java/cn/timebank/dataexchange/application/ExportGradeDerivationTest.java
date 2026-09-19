package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.application.ExportGradeDerivation.Cohort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `Design/*&#47;12-DataExport.md` §5.1's table, asserted directly. No database:
 * the derivation is arithmetic, and the point of keeping it dependency-free is
 * that this table can be checked without standing a schema up.
 */
class ExportGradeDerivationTest {

    /**
     * §5.1's worked example — a stage-1 entrant in 2026-2027 — read from the
     * vantage point of that same year, which is the case where the entry grade
     * has to be derived from a zero-year journey.
     */
    @ParameterizedTest
    @CsvSource({
            "2026, 6",
            "2027, 7",
            "2028, 8",
    })
    void aStage1EntrantAdvancesOneGradePerYear(int selectedYear, int expectedGrade) {
        Cohort cohort = new Cohort(2026, 2029, 6);

        assertThat(ExportGradeDerivation.gradeLevelIn(cohort, selectedYear, 2026)).isEqualTo(expectedGrade);
        assertThat(ExportGradeDerivation.enrolledIn(cohort, selectedYear)).isTrue();
    }

    /**
     * The row §5.1 writes as `Graduated (2029)`: from 2029 onward the student is
     * not enrolled, so they are absent from that year's export rather than
     * present with a blank grade. A blank cell would read as missing data, which
     * is a different claim from "not in the school that year".
     */
    @Test
    void theGraduationYearItselfIsNotAnEnrolledYear() {
        Cohort cohort = new Cohort(2026, 2029, 6);

        assertThat(ExportGradeDerivation.enrolledIn(cohort, 2029)).isFalse();
        assertThat(ExportGradeDerivation.enrolledIn(cohort, 2030)).isFalse();
        assertThat(ExportGradeDerivation.enrolledIn(cohort, 2025)).isFalse(); // before they arrived
    }

    /**
     * The same student seen from a later year. The stored grade has moved on, and
     * the derivation has to walk it back — this is the case that fails if
     * anything reads {@code grade_id} as though it were historical.
     */
    @Test
    void aPastYearIsDerivedFromTheStudentsPresentGradeNotReadFromIt() {
        // Enrolled 2026 at grade 6; it is now 2028-2029 and they are in grade 8.
        Cohort cohort = new Cohort(2026, 2029, 8);

        assertThat(ExportGradeDerivation.gradeLevelIn(cohort, 2026, 2028)).isEqualTo(6);
        assertThat(ExportGradeDerivation.gradeLevelIn(cohort, 2027, 2028)).isEqualTo(7);
        assertThat(ExportGradeDerivation.gradeLevelIn(cohort, 2028, 2028)).isEqualTo(8);
    }

    /** Stage 2 entrants start at 9 and run four years, so the same arithmetic must land on 9 without being told which stage it is. */
    @Test
    void aStage2EntrantEntersAtNine() {
        Cohort cohort = new Cohort(2024, 2028, 11); // enrolled 2024 at grade 9, now 2026 and in grade 11

        assertThat(ExportGradeDerivation.entryGrade(cohort, 2026)).isEqualTo(9);
        assertThat(ExportGradeDerivation.gradeLevelIn(cohort, 2024, 2026)).isEqualTo(9);
        assertThat(ExportGradeDerivation.gradeLevelIn(cohort, 2027, 2026)).isEqualTo(12);
        assertThat(ExportGradeDerivation.enrolledIn(cohort, 2027)).isTrue();
        assertThat(ExportGradeDerivation.enrolledIn(cohort, 2028)).isFalse();
        assertThat(ExportGradeDerivation.isInconsistent(cohort, 2026)).isFalse();
    }

    /**
     * §5.2.1: the derivation assumes no repeated or skipped year. A student who
     * repeated one lands on an entry grade that is neither 6 nor 9, and the
     * caller needs to know that rather than print a confidently wrong grade.
     */
    @Test
    void anEntryGradeThatIsNeitherSixNorNineIsReportedAsInconsistent() {
        Cohort repeated = new Cohort(2024, 2028, 10); // 2 years in but only at grade 10 ⇒ entry 8

        assertThat(ExportGradeDerivation.entryGrade(repeated, 2026)).isEqualTo(8);
        assertThat(ExportGradeDerivation.isInconsistent(repeated, 2026)).isTrue();

        // The control: the same shape of student who did not repeat is consistent.
        assertThat(ExportGradeDerivation.isInconsistent(new Cohort(2024, 2028, 11), 2026)).isFalse();
    }

    /** §5.3's "All years" case shows present state, so graduation is a question about the current year, not a selected one. */
    @Test
    void graduationIsJudgedAgainstTheCurrentYear() {
        Cohort cohort = new Cohort(2023, 2026, 8);

        assertThat(ExportGradeDerivation.hasGraduated(cohort, 2026)).isTrue();
        assertThat(ExportGradeDerivation.hasGraduated(cohort, 2025)).isFalse();
    }
}
