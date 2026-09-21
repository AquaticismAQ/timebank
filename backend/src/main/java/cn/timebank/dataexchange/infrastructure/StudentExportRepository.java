package cn.timebank.dataexchange.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * The Student List export (`Design/*&#47;13-DataExport.md` §6.1) — identity and
 * placement only, no service or time-coin figures.
 *
 * <p>Keyset-paginated on {@code id} rather than {@code OFFSET} (Design.md §16.1):
 * a walk of the whole table must not degrade into an O(n²) scan as it goes
 * deeper.</p>
 *
 * <p>This returns <b>ids</b> for grade, class and enrolment year and leaves the
 * caller to render labels. That is deliberate: the Grade column is not the
 * student's stored grade but the grade <em>derived for the selected year</em>
 * (§5.1), so a row cannot be rendered without the derivation, and having SQL join
 * a label it would then have to override would be the confusing half-answer that
 * issue 107 §3 describes.</p>
 */
@Repository
public class StudentExportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StudentExportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param gradeId   the student's <b>live</b> grade, used for scope and as the
     *                  fallback label when no year is selected — never as a past
     *                  year's grade
     * @param enrollmentAcademicYearId resolved to a start year by the caller, so a
     *                  student's cohort can be derived without a second query
     */
    /**
     * @param gradeSequence and {@code className} are the sort key, carried out
     *        so the caller can build the next page's cursor without a second
     *        lookup (issue 153). They are not written to the file — the Grade
     *        and Class columns are rendered from the ids above
     */
    public record ExportRow(String id, String studentNumber, String displayName,
                             String gradeId, String classId, String status,
                             String enrollmentAcademicYearId, Integer graduationYear, LocalDate joinedAt,
                             int gradeSequence, String className) {
    }

    /**
     * One page of students, restricted to {@code studentIds} when the caller's
     * scope produced a set.
     *
     * <p>A {@code null} set means no restriction — the whole school. The caller
     * must never pass an <b>empty</b> set: that is "your filters match nobody",
     * which is a different answer and would become {@code in ()}, a syntax error.
     * {@code ExportScope.matchesNobody()} exists so the call site handles it
     * before getting here.</p>
     */
    public List<ExportRow> page(ExportCursor.Roster after, int limit, Set<String> studentIds) {
        // Ordered by grade then class, which is how a school reads a roster
        // (issue 153). `grade.sequence`, never the id or the label: `grade_10`
        // sorts before `grade_6` as a string, and so does "Grade 10".
        //
        // The keyset moves with the order — see ExportCursor. A null cursor is
        // the first page; the row-value comparison is one predicate MySQL can
        // still resolve with an index.
        StringBuilder sql = new StringBuilder("""
                select s.id, s.student_number, s.display_name, s.grade_id, s.class_id, s.status,
                       s.enrollment_academic_year_id, s.graduation_year, s.joined_at,
                       g.sequence as grade_sequence, coalesce(c.name, '') as class_name
                from student s
                  join grade g on g.id = s.grade_id
                  left join class c on c.id = s.class_id
                where (:afterId is null
                       or (g.sequence, coalesce(c.name, ''), s.id) > (:afterGradeSequence, :afterClassName, :afterId))
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("afterId", after == null ? null : after.id())
                .addValue("afterGradeSequence", after == null ? null : after.gradeSequence())
                .addValue("afterClassName", after == null ? null : after.className())
                .addValue("limit", limit);
        if (studentIds != null) {
            sql.append(" and s.id in (:studentIds)");
            params.addValue("studentIds", studentIds);
        }
        sql.append(" order by g.sequence asc, coalesce(c.name, '') asc, s.id asc limit :limit");

        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new ExportRow(
                rs.getString("id"), rs.getString("student_number"), rs.getString("display_name"),
                rs.getString("grade_id"), rs.getString("class_id"), rs.getString("status"),
                rs.getString("enrollment_academic_year_id"),
                (Integer) rs.getObject("graduation_year"),
                rs.getObject("joined_at", LocalDate.class),
                rs.getInt("grade_sequence"), rs.getString("class_name")));
    }
}
