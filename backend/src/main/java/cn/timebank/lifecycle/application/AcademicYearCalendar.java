package cn.timebank.lifecycle.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeParseException;

/**
 * When a school year turns over — the one date that decides which academic year
 * "today" belongs to, and therefore the day promotion and graduation run
 * (`Design/*&#47;14-AnnualRollover.md` §2).
 *
 * <p>Configurable because 1 September is a convention, not a law: a school may
 * start a week earlier or later, and the date was previously hardcoded in three
 * places that each had to be found and changed together. One component owns it
 * now, so the rollover job, the academic-year rows it creates, and anything else
 * that needs the boundary cannot disagree about it.</p>
 *
 * <p><b>What this does not control.</b> Existing {@code academic_year} rows keep
 * the {@code start_date}/{@code end_date} they were created with, and every query
 * that scopes data to a year reads those stored dates rather than recomputing
 * from this setting. So changing the configuration affects years created
 * <em>after</em> the change, and never silently reinterprets a year that students
 * already have records in. `R__seed_base_data.sql` bootstraps the first two years
 * at 1 September as well — a repeatable SQL migration cannot read a Spring
 * property — so on a school that configures something else, the seeded years keep
 * their original dates and the first rolled-over year is the first to follow the
 * new setting.</p>
 */
@Component
public class AcademicYearCalendar {

    private static final Logger log = LoggerFactory.getLogger(AcademicYearCalendar.class);

    /** The convention the design, the seed and every deployment so far assume. */
    static final MonthDay DEFAULT_START = MonthDay.of(9, 1);

    private final MonthDay yearStart;

    AcademicYearCalendar(@Value("${timebank.lifecycle.academic-year-start:09-01}") String configured) {
        this.yearStart = parseOrFail(configured);
        if (!yearStart.equals(DEFAULT_START)) {
            // Worth a line at INFO: an operator debugging "why did promotion run
            // on the 20th" should not have to find the env file to learn that it
            // was told to.
            log.info("Academic year starts on {} (configured), not the default {}", yearStart, DEFAULT_START);
        }
    }

    /**
     * Rejects an unusable value rather than falling back.
     *
     * <p>Deliberately stricter than {@code timebank.import.historical-window-days},
     * which clamps a silly number into range. A number outside 1..60 has an
     * obvious nearest sane value; an unparseable or impossible <em>date</em> does
     * not, and quietly substituting 1 September would mean a school that
     * configured 20 August has its students promoted twelve days late with
     * nothing on screen saying so. Promotion is irreversible enough that
     * refusing to start is the kinder failure.</p>
     */
    private static MonthDay parseOrFail(String configured) {
        MonthDay parsed;
        try {
            // MonthDay's own format is `--MM-dd`; accept the friendlier `MM-dd`
            // an operator would actually type in an env file.
            parsed = MonthDay.parse(configured.startsWith("--") ? configured : "--" + configured.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("timebank.lifecycle.academic-year-start must be MM-DD, e.g. 09-01 — got: "
                    + configured, e);
        }
        if (parsed.getMonthValue() == 2 && parsed.getDayOfMonth() == 29) {
            // MonthDay happily represents 29 February; LocalDate.of throws for it
            // in three years out of four, so a rollover scheduled there would
            // simply not happen most years — the worst kind of bug, since nothing
            // fails and a whole cohort is not promoted.
            throw new IllegalStateException(
                    "timebank.lifecycle.academic-year-start must not be 02-29: it does not exist in most years, "
                            + "so the rollover would silently be skipped");
        }
        return parsed;
    }

    /**
     * Which academic year the given date falls in, named for the calendar year it
     * starts in. Anything on or after the boundary belongs to the year starting
     * now; anything before it still belongs to the previous one.
     */
    public int academicYearStartFor(LocalDate date) {
        return MonthDay.from(date).isBefore(yearStart) ? date.getYear() - 1 : date.getYear();
    }

    /** First day of the academic year named for {@code startYear}. */
    public LocalDate startOf(int startYear) {
        return yearStart.atYear(startYear);
    }

    /**
     * Last day of that year — the day before the next one begins, rather than a
     * hardcoded 31 August, so the two always meet exactly with no gap or overlap
     * whatever the boundary is set to.
     */
    public LocalDate endOf(int startYear) {
        return startOf(startYear + 1).minusDays(1);
    }

    public MonthDay boundary() {
        return yearStart;
    }
}
