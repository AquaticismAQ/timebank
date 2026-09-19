package cn.timebank.dataexchange.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The two per-student reports of `Design/*&#47;12-DataExport.md` §6.3 and §6.5 —
 * Student Completion and Student Participation.
 *
 * <p><b>Not built on {@code ReportService}</b> (F-7). Those reports aggregate to
 * the <em>grade</em>: they answer "what fraction of grade 8 is compliant". These
 * answer "where does each student stand", so the grain is a student and there is
 * nothing to reuse but the name.</p>
 *
 * <p>Every row is produced for a student in scope even when that student has done
 * nothing at all: §6.3's exit criterion is explicit that a student with zero
 * approved records appears with {@code 0} hours and a {@code 0.00%} rate rather
 * than being absent. They <em>are</em> enrolled, and absence would read as "not
 * in this school", which is a different and wrong claim — hence the left joins
 * below rather than the inner joins the aggregates would naturally want.</p>
 */
@Repository
public class StudentReportExportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StudentReportExportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param projectsParticipated projects with at least one <b>approved</b> record in the
     *                             window — not approved registrations, which §6.5 renamed
     *                             away from precisely because the two were conflated
     * @param approvedMinutes      summed from approved records only
     * @param creditedUnits        centi-coins credited in the window; the same number as the
     *                             hours under today's rule, and a different one the moment a
     *                             rule with another rate is published, which is why §6.3
     *                             keeps both columns
     */
    public record CompletionRow(String studentId, String studentNumber, String studentName,
                                 String gradeId, String classId, String enrollmentAcademicYearId,
                                 Integer graduationYear,
                                 int projectsParticipated, long approvedMinutes, long creditedUnits) {
    }

    public record ParticipationRow(String studentId, String studentNumber, String studentName,
                                    String gradeId, String classId, String enrollmentAcademicYearId,
                                    Integer graduationYear,
                                    int registeredProjects, String registeredProjectNames,
                                    int participatedProjects, String participatedProjectNames,
                                    long approvedMinutes, long creditedUnits) {
    }

    public List<CompletionRow> completionPage(String afterId, int limit, Set<String> studentIds, Integer yearStart) {
        StringBuilder sql = new StringBuilder("""
                select s.id, s.student_number, s.display_name, s.grade_id, s.class_id,
                       s.enrollment_academic_year_id, s.graduation_year,
                       count(distinct case when sr.status = 'APPROVED' and sr.project_id is not null
                                           then sr.project_id end) as projects_participated,
                       coalesce(sum(case when sr.status = 'APPROVED' then sr.duration_minutes else 0 end), 0) as approved_minutes,
                       coalesce(sum(case when sr.status = 'APPROVED' then sr.credited_units else 0 end), 0) as credited_units
                from student s
                  left join service_record sr
                         on sr.student_id = s.id
                        and sr.status = 'APPROVED'
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("afterId", afterId == null ? "" : afterId)
                .addValue("limit", limit);
        appendYearPredicate(sql, params, yearStart, "sr");
        sql.append(" where s.id > :afterId");
        appendStudentPredicate(sql, params, studentIds, "s");
        sql.append(" group by s.id order by s.id asc limit :limit");

        return jdbcTemplate.query(sql.toString(), params, (rs, i) -> new CompletionRow(
                rs.getString("id"), rs.getString("student_number"), rs.getString("display_name"),
                rs.getString("grade_id"), rs.getString("class_id"),
                rs.getString("enrollment_academic_year_id"), (Integer) rs.getObject("graduation_year"),
                rs.getInt("projects_participated"), rs.getLong("approved_minutes"),
                rs.getLong("credited_units")));
    }

    /**
     * Registrations and participation side by side.
     *
     * <p>The counts and the name lists come from the same subqueries, so they
     * cannot disagree — §3.3's exit criterion asks for exactly that, and counting
     * in one place while listing names in another is how they would drift.</p>
     *
     * <p>{@code CONFIRMED} registrations only: an application still pending is
     * not a commitment the student made and the school accepted, so counting it
     * would inflate the very gap this report exists to show.</p>
     */
    public List<ParticipationRow> participationPage(String afterId, int limit, Set<String> studentIds,
                                                     Integer yearStart) {
        StringBuilder sql = new StringBuilder("""
                select s.id, s.student_number, s.display_name, s.grade_id, s.class_id,
                       s.enrollment_academic_year_id, s.graduation_year,
                       (select count(distinct e.project_id) from enrollment e
                         where e.student_id = s.id and e.status = 'CONFIRMED') as registered_count,
                       (select group_concat(distinct p.title order by p.title separator ', ')
                          from enrollment e join project p on p.id = e.project_id
                         where e.student_id = s.id and e.status = 'CONFIRMED') as registered_names,
                       (select count(distinct sr.project_id) from service_record sr
                         where sr.student_id = s.id and sr.status = 'APPROVED' and sr.project_id is not null
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("afterId", afterId == null ? "" : afterId)
                .addValue("limit", limit);
        appendYearPredicate(sql, params, yearStart, "sr");
        sql.append(") as participated_count,\n"
                + "                       (select group_concat(distinct p.title order by p.title separator ', ')\n"
                + "                          from service_record sr join project p on p.id = sr.project_id\n"
                + "                         where sr.student_id = s.id and sr.status = 'APPROVED'\n");
        appendYearPredicate(sql, params, yearStart, "sr");
        sql.append(") as participated_names,\n"
                + "                       (select coalesce(sum(sr.duration_minutes), 0) from service_record sr\n"
                + "                         where sr.student_id = s.id and sr.status = 'APPROVED'\n");
        appendYearPredicate(sql, params, yearStart, "sr");
        sql.append(") as approved_minutes,\n"
                + "                       (select coalesce(sum(sr.credited_units), 0) from service_record sr\n"
                + "                         where sr.student_id = s.id and sr.status = 'APPROVED'\n");
        appendYearPredicate(sql, params, yearStart, "sr");
        sql.append(") as credited_units\n"
                + "                from student s\n"
                + "                where s.id > :afterId\n");
        appendStudentPredicate(sql, params, studentIds, "s");
        sql.append(" order by s.id asc limit :limit");

        return jdbcTemplate.query(sql.toString(), params, (rs, i) -> new ParticipationRow(
                rs.getString("id"), rs.getString("student_number"), rs.getString("display_name"),
                rs.getString("grade_id"), rs.getString("class_id"),
                rs.getString("enrollment_academic_year_id"), (Integer) rs.getObject("graduation_year"),
                rs.getInt("registered_count"), rs.getString("registered_names"),
                rs.getInt("participated_count"), rs.getString("participated_names"),
                rs.getLong("approved_minutes"), rs.getLong("credited_units")));
    }

    /**
     * The annual requirement in force for a year, per stage band.
     *
     * <p>Q-6: for a <em>past</em> year this must be the rule that was in effect
     * then, not today's. A completion rate recomputed against a newer, higher
     * requirement would retroactively fail students who met the rule they were
     * actually held to. Resolved by {@code effective_from}, taking the latest
     * published version that had started by the end of the year in question.</p>
     */
    public Optional<RequiredMinutes> requiredMinutesFor(int yearStart) {
        List<RequiredMinutes> rows = jdbcTemplate.query("""
                select middle_annual_required_minutes, high_annual_required_minutes
                from rule_version
                where status = 'PUBLISHED' and effective_from <= :yearEnd
                order by effective_from desc, published_at desc
                limit 1
                """, new MapSqlParameterSource("yearEnd", LocalDate.of(yearStart + 1, 8, 31)),
                (rs, i) -> new RequiredMinutes(rs.getInt("middle_annual_required_minutes"),
                        rs.getInt("high_annual_required_minutes")));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Junior band (grades 6-8) and senior band (grades 9-12) annual requirements, in minutes. */
    public record RequiredMinutes(int middleAnnualMinutes, int highAnnualMinutes) {
    }

    private void appendStudentPredicate(StringBuilder sql, MapSqlParameterSource params,
                                         Set<String> studentIds, String alias) {
        if (studentIds == null) {
            return;
        }
        if (studentIds.isEmpty()) {
            // `in ()` is a MySQL syntax error, and "matches nobody" is a real
            // answer rather than a caller mistake.
            sql.append(" and 1 = 0");
            return;
        }
        sql.append(" and ").append(alias).append(".id in (:studentIds)");
        params.addValue("studentIds", studentIds);
    }

    /**
     * The academic year runs 1 September to 31 August, so a year is not a
     * calendar year — a {@code year(service_date)} comparison puts every autumn
     * record in the wrong one.
     */
    private void appendYearPredicate(StringBuilder sql, MapSqlParameterSource params,
                                      Integer yearStart, String alias) {
        if (yearStart != null) {
            sql.append(" and ").append(alias).append(".service_date >= :yearFrom")
               .append(" and ").append(alias).append(".service_date <= :yearTo");
            params.addValue("yearFrom", LocalDate.of(yearStart, 9, 1));
            params.addValue("yearTo", LocalDate.of(yearStart + 1, 8, 31));
        }
    }
}
