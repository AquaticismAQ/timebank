package cn.timebank.dataexchange.infrastructure;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Where a paged export has got to (issue 153).
 *
 * <p>An export walks its table by keyset — `where (key) > (:lastKey) order by
 * (key)` — rather than by `OFFSET`, so a walk of the whole school does not
 * degrade into an O(n²) scan as it goes deeper (Design.md §16.1).</p>
 *
 * <p><b>The cursor and the ordering are one thing.</b> They used to be the row
 * id, which made them trivially consistent and made every export arrive in an
 * order no reader could see a reason for. Sorting the output means moving the
 * cursor with it: an `ORDER BY` changed on its own would leave the walk
 * resuming from a position in a different sequence, which does not look wrong —
 * it silently skips rows and repeats others.</p>
 *
 * <p>The row id is always the last component even where the sort column looks
 * unique enough. Two students in one class, or two records on one date, have to
 * break their tie the same way every page, or a row on the boundary can be
 * dropped or served twice.</p>
 */
public final class ExportCursor {

    private ExportCursor() {
    }

    /**
     * A roster-shaped export: grade, then class, then id.
     *
     * @param gradeSequence {@code grade.sequence}, never the id or the label —
     *        {@code grade_10} sorts before {@code grade_6} as a string, and so
     *        does "Grade 10" before "Grade 6". Only the sequence orders a school
     *        the way a school does
     * @param className the class's own name; blank rather than null for a
     *        student with no class, so the comparison stays total
     */
    public record Roster(int gradeSequence, String className, String id) {
    }

    /** A date-shaped export: the day the thing happened, then id. */
    public record ByDate(LocalDate date, String id) {
    }

    /** A timestamp-shaped export: the moment the thing happened, then id. */
    public record ByInstant(LocalDateTime at, String id) {
    }
}
