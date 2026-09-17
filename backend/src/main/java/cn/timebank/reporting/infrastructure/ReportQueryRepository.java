package cn.timebank.reporting.infrastructure;

import cn.timebank.security.application.ScopeDescriptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * B5.7/B5.8: every query here is a single {@code GROUP BY} statement
 * against `service_record`/`review_decision`/`project`/`student`, never a
 * per-row Java loop (Design.md D6, §16.2) — the same legitimate
 * cross-module read-model pattern {@code ReviewQueueRepository} (B3.6)
 * established. Row shapes are purpose-built records, not entities.
 */
@Repository
public class ReportQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReportQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record GradeParticipationRow(String gradeId, int totalStudents, int participatedStudents) {
    }

    /**
     * Issue 093 §1: the population is every student who was {@code ACTIVE}
     * and already enrolled by the end of the window — {@code
     * student.enrollment_academic_year_id} is the year a student entered the
     * school, so a cohort that enrolled <em>after</em> the year being asked
     * about must not sit in its denominator as a guaranteed non-participant.
     * The reverse correction is not available: `status` is a present-tense
     * fact and the schema keeps no historical roster, so a student who has
     * since left is missing from a past year's population entirely.
     */
    public List<GradeParticipationRow> participationByGrade(LocalDate start, LocalDate end) {
        String sql = """
                select s.grade_id as grade_id,
                       count(distinct s.id) as total,
                       count(distinct case when sr.id is not null then s.id end) as participated
                from student s
                join academic_year ay on ay.id = s.enrollment_academic_year_id and ay.start_date <= :end
                left join service_record sr on sr.student_id = s.id and sr.status <> 'DRAFT'
                       and sr.service_date between :start and :end
                where s.status = 'ACTIVE'
                group by s.grade_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("start", start).addValue("end", end);
        return jdbcTemplate.query(sql, params, (rs, rowNum) ->
                new GradeParticipationRow(rs.getString("grade_id"), rs.getInt("total"), rs.getInt("participated")));
    }

    public record StudentCompletionRow(String gradeId, String studentId, int completedMinutes) {
    }

    /** One row per active student — bounded by student count (≤5,000), not by record count; the threshold comparison happens in Java over this already-aggregated set. Same population rule as {@link #participationByGrade}. */
    public List<StudentCompletionRow> completedMinutesByStudent(LocalDate start, LocalDate end) {
        String sql = """
                select s.grade_id as grade_id, s.id as student_id,
                       coalesce(sum(sr.duration_minutes), 0) as completed_minutes
                from student s
                join academic_year ay on ay.id = s.enrollment_academic_year_id and ay.start_date <= :end
                left join service_record sr on sr.student_id = s.id and sr.status = 'APPROVED'
                       and sr.service_date between :start and :end
                where s.status = 'ACTIVE'
                group by s.grade_id, s.id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("start", start).addValue("end", end);
        return jdbcTemplate.query(sql, params, (rs, rowNum) ->
                new StudentCompletionRow(rs.getString("grade_id"), rs.getString("student_id"), rs.getInt("completed_minutes")));
    }

    public record MonthMinutesRow(String month, int minutes) {
    }

    public List<MonthMinutesRow> serviceMinutesByMonth(LocalDate start, LocalDate end) {
        String sql = """
                select date_format(sr.service_date, '%Y-%m') as ym, sum(sr.duration_minutes) as minutes
                from service_record sr
                where sr.status = 'APPROVED' and sr.service_date between :start and :end
                group by ym
                order by ym
                """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("start", start).addValue("end", end);
        return jdbcTemplate.query(sql, params, (rs, rowNum) ->
                new MonthMinutesRow(rs.getString("ym"), rs.getInt("minutes")));
    }

    public record ReviewerFigureRow(String reviewerId, long decisions, double avgHours) {
    }

    public List<ReviewerFigureRow> reviewEfficiencyByReviewer(LocalDate start, LocalDate end) {
        String sql = """
                select rd.reviewer_id as reviewer_id, count(*) as decisions,
                       avg(timestampdiff(second, sr.submitted_at, rd.created_at)) / 3600.0 as avg_hours
                from review_decision rd
                join service_record sr on sr.id = rd.service_record_id
                where rd.created_at >= :start and rd.created_at < :end and sr.submitted_at is not null
                  -- `service_record.submitted_at` only ever holds the *current* submission — a
                  -- returned-then-resubmitted record overwrites it, so an earlier decision from the
                  -- prior (already-superseded) review round would compare against a submission time
                  -- from its own future, producing a large negative duration (found live: -73h on one
                  -- record dragged the whole average to -0.3 days). A decision from a stale round
                  -- always has created_at before the record's current submitted_at; only the
                  -- decision(s) responding to the current submission satisfy created_at >= submitted_at.
                  and rd.created_at >= sr.submitted_at
                group by rd.reviewer_id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", start.atStartOfDay()).addValue("end", end.plusDays(1).atStartOfDay());
        return jdbcTemplate.query(sql, params, (rs, rowNum) ->
                new ReviewerFigureRow(rs.getString("reviewer_id"), rs.getLong("decisions"), rs.getDouble("avg_hours")));
    }

    public record TopProjectRow(String projectId, String title, long participants, long totalMinutes) {
    }

    /**
     * Issue 093 §1 and §8 together.
     *
     * <p><b>§8 — why two scalar subqueries rather than one grouped join.</b>
     * Joining {@code enrollment} <em>and</em> {@code service_record} fans the
     * rows out: each approved record is paired with every confirmed enrolment,
     * so {@code sum(duration_minutes)} came back multiplied by the participant
     * count (150 minutes reported as 600). {@code count(distinct ...)} absorbed
     * the same fan-out on the other column, which is why only the hours looked
     * wrong. It went unnoticed because no seeded record had a
     * {@code project_id}, so every sum was 0 — and 0 x 4 is still 0. Keep the
     * aggregates independent; do not "simplify" this back into a grouped join.</p>
     *
     * <p><b>§1 — why the year key is the project, not the service date.</b>
     * This is the only report whose scoping key is the project itself. A project
     * belongs to the academic year its own schedule falls in
     * ({@code project.start_at}) — {@code enrollment} carries no year, and its
     * {@code applied_at} would put a project registered in August into the
     * previous year. The records are bounded separately by {@code service_date}
     * so a project's hours never include service logged against it in a
     * different year from the one being asked about.</p>
     */
    public List<TopProjectRow> topProjects(int limit, LocalDate start, LocalDate end) {
        String sql = """
                select p.id as project_id, p.title as title,
                       (select count(distinct e.student_id) from enrollment e
                         where e.project_id = p.id and e.status in ('CONFIRMED', 'COMPLETED')) as participants,
                       (select coalesce(sum(sr.duration_minutes), 0) from service_record sr
                         where sr.project_id = p.id and sr.status = 'APPROVED'
                           and sr.service_date between :start and :end) as total_minutes
                from project p
                where exists (select 1 from enrollment e
                               where e.project_id = p.id and e.status in ('CONFIRMED', 'COMPLETED'))
                  and p.start_at >= :startAt and p.start_at < :endAtExclusive
                order by participants desc, total_minutes desc
                limit :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit)
                .addValue("start", start).addValue("end", end)
                .addValue("startAt", start.atStartOfDay()).addValue("endAtExclusive", end.plusDays(1).atStartOfDay());
        return jdbcTemplate.query(sql, params, (rs, rowNum) ->
                new TopProjectRow(rs.getString("project_id"), rs.getString("title"), rs.getLong("participants"), rs.getLong("total_minutes")));
    }

    public record AdminStatsRow(long totalStudents, long activeStudents, long totalProjects, long activeProjects,
                                 long approvedThisMonth, long totalServiceHoursThisYear) {
    }

    public AdminStatsRow adminStats(LocalDate monthStart, LocalDate monthEndExclusive, LocalDate yearStart, LocalDate yearEnd) {
        String sql = """
                select
                    (select count(*) from student) as total_students,
                    (select count(*) from student where status = 'ACTIVE') as active_students,
                    (select count(*) from project) as total_projects,
                    (select count(*) from project where status in ('PUBLISHED', 'REGISTRATION_OPEN', 'IN_PROGRESS')) as active_projects,
                    (select count(*) from service_record where status = 'APPROVED'
                        and updated_at >= :monthStart and updated_at < :monthEnd) as approved_this_month,
                    (select coalesce(sum(duration_minutes), 0) from service_record where status = 'APPROVED'
                        and service_date between :yearStart and :yearEnd) as year_minutes
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("monthStart", monthStart.atStartOfDay())
                .addValue("monthEnd", monthEndExclusive.atStartOfDay())
                .addValue("yearStart", yearStart).addValue("yearEnd", yearEnd);
        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new AdminStatsRow(
                rs.getLong("total_students"), rs.getLong("active_students"), rs.getLong("total_projects"),
                rs.getLong("active_projects"), rs.getLong("approved_this_month"), rs.getLong("year_minutes") / 60));
    }

    public long scopedActiveStudentCount(ScopeDescriptor scope) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String scopeClause = switch (scope) {
            case ScopeDescriptor.All ignored -> "";
            case ScopeDescriptor.Grades grades -> {
                params.addValue("gradeIds", nonEmpty(grades.gradeIds()));
                yield " and s.grade_id in (:gradeIds)";
            }
            case ScopeDescriptor.Classes classes -> {
                params.addValue("classIds", nonEmpty(classes.classIds()));
                yield " and s.class_id in (:classIds)";
            }
            case ScopeDescriptor.Self ignored -> " and 1 = 0";
            case ScopeDescriptor.Projects ignored -> " and 1 = 0";
        };
        String sql = "select count(*) from student s where s.status = 'ACTIVE'" + scopeClause;
        Long result = jdbcTemplate.queryForObject(sql, params, Long.class);
        return result == null ? 0 : result;
    }

    private Set<String> nonEmpty(Set<String> ids) {
        return ids.isEmpty() ? Set.of("__none__") : ids;
    }
}
