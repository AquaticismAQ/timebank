package cn.timebank.dataexchange.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * The Service Records export (`Design/*&#47;12-DataExport.md` §6.2) — one row per
 * <b>submitted</b> record, in every review state.
 *
 * <p>Keyset (seek) pagination on {@code id}, not {@code OFFSET}, per Design.md
 * §16.1. This is the one export large enough to genuinely need chunking.</p>
 *
 * <p>Two properties this query is responsible for, both of them things issue 107
 * found wrong in a delivered file:</p>
 *
 * <ul>
 *   <li><b>Drafts are excluded in SQL</b>, not filtered later. An unsubmitted
 *       draft is the student's private working state, not a claim made to the
 *       school, so it belongs in no export — and excluding it here means no
 *       caller can forget.</li>
 *   <li><b>Rows are selected by student</b>, not by the record's grade snapshot.
 *       A record filed last year carries the grade the student was in then; a
 *       teacher's scope is about who they are responsible for <em>now</em>.
 *       Filtering on the snapshot would silently drop that record from their
 *       export — §4.4's case, and the behavioural reason
 *       {@code ExportScopeResolver} resolves to a student set first.</li>
 * </ul>
 */
@Repository
public class ServiceRecordExportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ServiceRecordExportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param subject     project title for project-sourced records, organisation name for
     *                    self-initiated ones — §6.2's single `Project / Organisation` column
     * @param creditedUnits centi-coins, and <b>null on every unapproved row</b>; the caller
     *                    renders that as an empty cell rather than a zero, because zero would
     *                    be a claim that the record credited nothing when in fact it has not
     *                    been decided yet
     */
    public record ExportRow(String id, String studentId, String studentNumber, String studentName,
                             String gradeId, String classId, LocalDate serviceDate,
                             String serviceCategory, String subject,
                             int durationMinutes, Integer creditedUnits, String status) {
    }

    /**
     * One page of submitted records.
     *
     * <p>{@code service_record.student_name} is a snapshot taken at submission,
     * so the student's number and current name are joined from {@code student}:
     * §6.2 wants a row a reader can match against the roster, and the roster is
     * present-tense.</p>
     */
    public List<ExportRow> page(String afterId, int limit, Set<String> studentIds,
                                 LocalDate yearFrom, LocalDate yearTo) {
        StringBuilder sql = new StringBuilder("""
                select sr.id, sr.student_id, s.student_number, s.display_name,
                       sr.grade_id, sr.class_id, sr.service_date,
                       sc.name_en as category_en, sc.name_zh as category_zh,
                       p.title as project_title, sr.organization_name,
                       sr.duration_minutes, sr.credited_units, sr.status
                from service_record sr
                  join student s on s.id = sr.student_id
                  left join service_category sc on sc.id = sr.service_category_id
                  left join project p on p.id = sr.project_id
                where sr.id > :afterId
                  and sr.status <> 'DRAFT'
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("afterId", afterId == null ? "" : afterId)
                .addValue("limit", limit);
        if (studentIds != null) {
            sql.append(" and sr.student_id in (:studentIds)");
            params.addValue("studentIds", studentIds);
        }
        if (yearFrom != null && yearTo != null) {
            // The window comes from the academic_year row, not from arithmetic on
            // the year number: the boundary is configurable, and a year keeps the
            // dates it was created with, so recomputing here would drift the moment
            // either changed.
            sql.append(" and sr.service_date >= :yearFrom and sr.service_date <= :yearTo");
            params.addValue("yearFrom", yearFrom);
            params.addValue("yearTo", yearTo);
        }
        sql.append(" order by sr.id asc limit :limit");

        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> new ExportRow(
                rs.getString("id"), rs.getString("student_id"),
                rs.getString("student_number"), rs.getString("display_name"),
                rs.getString("grade_id"), rs.getString("class_id"),
                rs.getObject("service_date", LocalDate.class),
                categoryLabel(rs.getString("category_en"), rs.getString("category_zh")),
                subjectLabel(rs.getString("project_title"), rs.getString("organization_name")),
                rs.getInt("duration_minutes"),
                (Integer) rs.getObject("credited_units"),
                rs.getString("status")));
    }

    /** Column values stay fixed English (§2.6), so the English category name is the value; the Chinese one is a fallback for a category that has only it. */
    private static String categoryLabel(String english, String chinese) {
        if (english != null && !english.isBlank()) {
            return english;
        }
        return chinese;
    }

    /** §6.2's combined column: a record is either project-sourced or self-initiated, never both. */
    private static String subjectLabel(String projectTitle, String organizationName) {
        if (projectTitle != null && !projectTitle.isBlank()) {
            return projectTitle;
        }
        return organizationName;
    }

    public long countAll() {
        Long result = jdbcTemplate.getJdbcTemplate()
                .queryForObject("select count(*) from service_record where status <> 'DRAFT'", Long.class);
        return result == null ? 0 : result;
    }
}
