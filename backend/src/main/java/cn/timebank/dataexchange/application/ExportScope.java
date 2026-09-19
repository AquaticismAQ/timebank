package cn.timebank.dataexchange.application;

import java.time.LocalDate;
import java.util.Set;

/**
 * Everything an export query needs to know about <em>which rows</em>, decided
 * once at request time (`11-DataImportExportOptimization.md` §2.5).
 *
 * <p>This carries the <b>criteria</b>, not a materialised list of student ids.
 * The criteria are fixed when the request is authorised — including the caller's
 * grade or class binding as it stood then — so the job cannot later widen or
 * narrow because someone's assignments changed in between. The id list is then
 * resolved from these criteria inside the job.</p>
 *
 * <p>An earlier version stored the resolved ids and dropped the list above a size
 * limit, falling back to "all students". That is a scope <em>widening</em> on
 * overflow, which is the wrong direction to fail in and the kind of thing that
 * reads as harmless until it is not. Criteria have no such failure mode: they are
 * a handful of ids whatever the school's size.</p>
 *
 * @param restriction       the scope restriction, or {@code null} for the whole school
 * @param selectedYearIndex calendar year the selected academic year starts in, or
 *                          {@code null} for "All years" (§5.3: nothing to derive
 *                          against, so the Grade column shows present state)
 * @param currentYearIndex  calendar year the current academic year starts in; needed
 *                          because entry grade is derived from how far a student has
 *                          already come, and only their present grade is stored
 * @param reviewerId        Review History's reviewer filter, already forced to the
 *                          caller for a teacher (§6.7); {@code null} means all
 * @param yearFrom          first day of the selected academic year, read from its
 *                          {@code academic_year} row rather than computed from the
 *                          configured boundary
 * @param yearTo            last day of the same year, same source; both {@code null}
 *                          when no year is selected
 */
record ExportScope(Restriction restriction, Integer selectedYearIndex, int currentYearIndex, String reviewerId,
                    LocalDate yearFrom, LocalDate yearTo) {

    /**
     * How the student set is narrowed.
     *
     * @param boundGradeIds the caller's assigned grades, or {@code null} if not grade-bound
     * @param boundClassIds the caller's assigned classes, or {@code null} if not class-bound
     * @param gradeFilter   the caller's chosen grade, or {@code null} for all
     * @param classFilter   the caller's chosen class, or {@code null} for all
     */
    record Restriction(Set<String> boundGradeIds, Set<String> boundClassIds,
                        String gradeFilter, String classFilter) {

        boolean isEmpty() {
            return boundGradeIds == null && boundClassIds == null && gradeFilter == null && classFilter == null;
        }
    }

    /** True when no student restriction applies at all — the whole school. */
    boolean allStudents() {
        return restriction == null;
    }

    boolean hasYear() {
        return selectedYearIndex != null;
    }

    /**
     * True when a real date window is known for the selected year.
     *
     * <p>Deliberately separate from {@link #hasYear()}, because the two can
     * differ: a job row written before the window was recorded carries a year
     * index and no dates. A query must then filter on nothing rather than invent a
     * boundary — a guessed 1 September would be a confident wrong answer for a
     * school whose year runs to different dates.</p>
     */
    boolean hasYearWindow() {
        return yearFrom != null && yearTo != null;
    }
}
