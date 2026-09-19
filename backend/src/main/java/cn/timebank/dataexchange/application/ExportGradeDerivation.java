package cn.timebank.dataexchange.application;

/**
 * Which grade a student was in during a given academic year
 * (`Design/*&#47;12-DataExport.md` §5.1).
 *
 * <p>{@code student.grade_id} is present-tense — it says where a student is
 * today — and the database keeps no historical roster, so a past year's grade
 * cannot be looked up. It can be derived, because the school's stage model
 * (`13-AnnualRollover.md` §2) fixes entry at grade 6 or grade 9 and advances one
 * grade per year:</p>
 *
 * <pre>
 *   entry grade = current grade − (current year − enrolment year)
 *   grade in Y  = entry grade + (Y − enrolment year)
 * </pre>
 *
 * <p>Deliberately pure and repository-free: it is the one piece of export logic
 * whose correctness is a matter of arithmetic against §5.1's table rather than
 * of SQL, and keeping it dependency-free is what lets that table be asserted
 * directly. Years are handled as the calendar year an academic year
 * <em>starts</em> in — 2026 for 2026-2027 — never as the {@code code} string,
 * because parsing "2026-2027" is one more way to be wrong about something the
 * {@code start_date} already states.</p>
 *
 * <p><b>Nothing here reads {@code student.status}.</b> §5.4 is explicit that
 * "graduated" in an export is always derived from the graduation year: the
 * derivation is right before the annual rollover exists and right again if a
 * year's run is ever missed, and the status column is wrong in both of those
 * cases.</p>
 */
final class ExportGradeDerivation {

    private ExportGradeDerivation() {
    }

    /**
     * One student's cohort facts, as the export query reads them.
     *
     * @param enrolmentYearIndex calendar year the student's enrolment academic year began
     * @param graduationYear     {@code student.graduation_year}
     * @param liveGradeLevel     numeric level of the student's present {@code grade_id} (6..12)
     */
    record Cohort(int enrolmentYearIndex, int graduationYear, int liveGradeLevel) {
    }

    /**
     * Was this student in the school during the year starting {@code yearIndex}?
     *
     * <p>A student fails this for a year before they arrived and for the year
     * they graduate onward. §5.1 requires them to be <b>absent</b> from that
     * year's export rather than present with an empty grade — a blank cell reads
     * as missing data, which is a different claim from "not enrolled".</p>
     */
    static boolean enrolledIn(Cohort cohort, int yearIndex) {
        return yearIndex >= cohort.enrolmentYearIndex() && yearIndex <= cohort.graduationYear() - 1;
    }

    /**
     * The grade level this student held in the year starting {@code yearIndex}.
     *
     * <p>{@code currentYearIndex} is needed because the entry grade is itself
     * derived — from how far the student has already come — and only the present
     * grade is stored.</p>
     */
    static int gradeLevelIn(Cohort cohort, int yearIndex, int currentYearIndex) {
        int entryGrade = cohort.liveGradeLevel() - (currentYearIndex - cohort.enrolmentYearIndex());
        return entryGrade + (yearIndex - cohort.enrolmentYearIndex());
    }

    /**
     * The entry grade implied by a cohort — 6 or 9 for any consistent student.
     *
     * <p>Exposed because a value that is neither is the signal that this
     * student repeated or skipped a year, which §5.2.1 says the system does not
     * model and `13-AnnualRollover.md` §5's check is the only thing that
     * surfaces. Callers use it to avoid printing a confidently wrong grade.</p>
     */
    static int entryGrade(Cohort cohort, int currentYearIndex) {
        return cohort.liveGradeLevel() - (currentYearIndex - cohort.enrolmentYearIndex());
    }

    /** True when the entry grade lands on neither stage boundary, i.e. the derivation cannot be trusted for this student. */
    static boolean isInconsistent(Cohort cohort, int currentYearIndex) {
        int entry = entryGrade(cohort, currentYearIndex);
        return entry != 6 && entry != 9;
    }

    /**
     * Has this student's graduation year already passed, as of {@code currentYearIndex}?
     *
     * <p>Only relevant to the "All years" case (§5.3), where there is no year to
     * derive against and the column therefore shows the present state.</p>
     */
    static boolean hasGraduated(Cohort cohort, int currentYearIndex) {
        return currentYearIndex >= cohort.graduationYear();
    }
}
