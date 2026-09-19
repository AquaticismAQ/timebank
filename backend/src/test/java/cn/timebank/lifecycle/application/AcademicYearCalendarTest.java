package cn.timebank.lifecycle.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The boundary arithmetic, and the two configuration values that must be refused. */
class AcademicYearCalendarTest {

    private static AcademicYearCalendar calendar(String configured) {
        return new AcademicYearCalendar(configured);
    }

    @Test
    void theDefaultIsTheFirstOfSeptember() {
        AcademicYearCalendar c = calendar("09-01");

        assertThat(c.boundary()).isEqualTo(MonthDay.of(9, 1));
        // The boundary day itself belongs to the new year, not the old one.
        assertThat(c.academicYearStartFor(LocalDate.of(2026, 9, 1))).isEqualTo(2026);
        assertThat(c.academicYearStartFor(LocalDate.of(2026, 8, 31))).isEqualTo(2025);
        assertThat(c.startOf(2026)).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(c.endOf(2026)).isEqualTo(LocalDate.of(2027, 8, 31));
    }

    /** An earlier boundary moves which year August belongs to — the whole point of making it configurable. */
    @Test
    void anEarlierBoundaryReclassifiesLateAugust() {
        AcademicYearCalendar c = calendar("08-20");

        assertThat(c.academicYearStartFor(LocalDate.of(2026, 8, 20))).isEqualTo(2026);
        assertThat(c.academicYearStartFor(LocalDate.of(2026, 8, 19))).isEqualTo(2025);
        assertThat(c.startOf(2026)).isEqualTo(LocalDate.of(2026, 8, 20));
        // End is derived from the next start, so the two meet with no gap.
        assertThat(c.endOf(2026)).isEqualTo(LocalDate.of(2027, 8, 19));
    }

    /** A January boundary is legal and must not wrap: the year is then the calendar year. */
    @Test
    void aJanuaryBoundaryIsHandledWithoutWrapping() {
        AcademicYearCalendar c = calendar("01-01");

        assertThat(c.academicYearStartFor(LocalDate.of(2026, 1, 1))).isEqualTo(2026);
        assertThat(c.academicYearStartFor(LocalDate.of(2026, 12, 31))).isEqualTo(2026);
        assertThat(c.endOf(2026)).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    /** Consecutive years must abut exactly, whatever the boundary — no gap, no overlap. */
    @Test
    void consecutiveYearsAbutExactly() {
        for (String boundary : new String[]{"09-01", "08-20", "01-01", "02-28", "12-31"}) {
            AcademicYearCalendar c = calendar(boundary);
            assertThat(c.endOf(2026).plusDays(1))
                    .as("with boundary %s, year 2027 must begin the day after 2026 ends", boundary)
                    .isEqualTo(c.startOf(2027));
        }
    }

    @Test
    void theMonthDayNativeFormIsAlsoAccepted() {
        assertThat(calendar("--09-01").boundary()).isEqualTo(MonthDay.of(9, 1));
        assertThat(calendar(" 09-01 ").boundary()).as("an env file's stray whitespace").isEqualTo(MonthDay.of(9, 1));
    }

    /**
     * Refused rather than defaulted. Substituting 1 September for an unreadable
     * value would promote a school that asked for 20 August twelve days late with
     * nothing saying so, and promotion is not something to be quietly wrong about.
     */
    @Test
    void anUnreadableValueRefusesToStart() {
        assertThatThrownBy(() -> calendar("first of September"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be MM-DD");
        assertThatThrownBy(() -> calendar("13-01"))
                .as("month 13 is not a month")
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 29 February is the trap worth a dedicated guard: {@code MonthDay} accepts
     * it, so it looks valid, and then the date does not exist in three years out
     * of four — a rollover scheduled there would simply not happen, silently,
     * leaving a cohort unpromoted with nothing in the logs.
     */
    @Test
    void the29thOfFebruaryIsRefusedBecauseItUsuallyDoesNotExist() {
        assertThatThrownBy(() -> calendar("02-29"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("02-29");

        // The control: 28 February is a perfectly ordinary boundary.
        assertThat(calendar("02-28").boundary()).isEqualTo(MonthDay.of(2, 28));
    }
}
