package cn.timebank.dataexchange.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * The Student List export (`Design/*&#47;12-DataExport.md` §6.1) — identity and
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
    public record ExportRow(String id, String studentNumber, String displayName,
                             String gradeId, String classId, String status,
                             String enrollmentAcademicYearId, Integer graduationYear, LocalDate joinedAt) {
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
    public List<ExportRow> page(String afterId, int limit, Set<String> studentIds) {
        StringBuilder sql = new StringBuilder("""
                select id, student_number, display_name, grade_id, class_id, status,
                       enrollment_academic_year_id, graduation_year, joined_at
                from student
                where id > :afterId
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("afterId", afterId == null ? "" : afterId)
                .addValue("limit", limit);
        if (studentIds != null) {
            sql.append(" and id in (:studentIds)");
            params.addValue("studentIds", studentIds);
        }
        sql.append(" order by id asc limit :limit");

        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new ExportRow(
                rs.getString("id"), rs.getString("student_number"), rs.getString("display_name"),
                rs.getString("grade_id"), rs.getString("class_id"), rs.getString("status"),
                rs.getString("enrollment_academic_year_id"),
                (Integer) rs.getObject("graduation_year"),
                rs.getObject("joined_at", LocalDate.class)));
    }
}
