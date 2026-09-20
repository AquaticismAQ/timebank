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

    public record CategoryParticipationRow(String categoryId, long participants, long totalMinutes) {
    }

    /**
     * Participation grouped by the project's service category (issue 134).
     *
     * <p>Counts enrolments rather than distinct students — see
     * {@code TopProjectsReportDto.CategoryFigure} for why a pie needs the
     * additive one. The year window is the project's {@code start_at}, matching
     * {@link #topProjects}, so the same project belongs to the same year in
     * both halves of this report.</p>
     */
    public List<CategoryParticipationRow> participationByCategory(LocalDate start, LocalDate end, ScopeDescriptor scope) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", start).addValue("end", end)
                .addValue("startAt", start.atStartOfDay()).addValue("endAtExclusive", end.plusDays(1).atStartOfDay());
        // Scoped through the *enrolled student*, not the project's eligible
        // grades: "participation in my grades" is about who took part, and a
        // project open to four year groups is not four times mine.
        String enrolmentScope = scope instanceof ScopeDescriptor.All ? ""
                : " and exists (select 1 from student st where st.id = e.student_id"
                        + studentScopeClause(scope, "st", params) + ")";
        String sql = """
                select p.category_id as category_id,
                       count(*) as participants,
                       (select coalesce(sum(sr.duration_minutes), 0) from service_record sr
                         join project p2 on p2.id = sr.project_id
                         where p2.category_id = p.category_id and sr.status = 'APPROVED'
                           and sr.service_date between :start and :end%s) as total_minutes
                from project p
                join enrollment e on e.project_id = p.id and e.status in ('CONFIRMED', 'COMPLETED')%s
                where p.start_at >= :startAt and p.start_at < :endAtExclusive
                group by p.category_id
                order by participants desc
                """.formatted(recordScopeClause(scope, "sr", params), enrolmentScope);
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> new CategoryParticipationRow(
                rs.getString("category_id"), rs.getLong("participants"), rs.getLong("total_minutes")));
    }

    public record DayMinutesRow(String day, int minutes) {
    }

    /** Approved service minutes per calendar day. Only days with service come back; the caller fills the rest (issue 133). */
    public List<DayMinutesRow> serviceMinutesByDay(LocalDate start, LocalDate end, ScopeDescriptor scope) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("start", start).addValue("end", end);
        String sql = """
                select date_format(sr.service_date, '%%Y-%%m-%%d') as ymd, sum(sr.duration_minutes) as minutes
                from service_record sr
                where sr.status = 'APPROVED' and sr.service_date between :start and :end%s
                group by ymd
                order by ymd
                """.formatted(recordScopeClause(scope, "sr", params));
        return jdbcTemplate.query(sql, params, (rs, rowNum) ->
                new DayMinutesRow(rs.getString("ymd"), rs.getInt("minutes")));
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
    public List<TopProjectRow> topProjects(int limit, LocalDate start, LocalDate end, ScopeDescriptor scope) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit)
                .addValue("start", start).addValue("end", end)
                .addValue("startAt", start.atStartOfDay()).addValue("endAtExclusive", end.plusDays(1).atStartOfDay());
        // Scoped through the enrolled student, like the category figures: a
        // project's rank "in my grades" is how many of my pupils joined it, not
        // whether it happens to be open to them.
        String enrolmentScope = scope instanceof ScopeDescriptor.All ? ""
                : " and exists (select 1 from student st where st.id = e.student_id"
                        + studentScopeClause(scope, "st", params) + ")";
        String sql = """
                select p.id as project_id, p.title as title,
                       (select count(distinct e.student_id) from enrollment e
                         where e.project_id = p.id and e.status in ('CONFIRMED', 'COMPLETED')%1$s) as participants,
                       (select coalesce(sum(sr.duration_minutes), 0) from service_record sr
                         where sr.project_id = p.id and sr.status = 'APPROVED'
                           and sr.service_date between :start and :end%2$s) as total_minutes
                from project p
                where exists (select 1 from enrollment e
                               where e.project_id = p.id and e.status in ('CONFIRMED', 'COMPLETED')%1$s)
                  and p.start_at >= :startAt and p.start_at < :endAtExclusive
                order by participants desc, total_minutes desc
                limit :limit
                """.formatted(enrolmentScope, recordScopeClause(scope, "sr", params));
        return jdbcTemplate.query(sql, params, (rs, rowNum) ->
                new TopProjectRow(rs.getString("project_id"), rs.getString("title"), rs.getLong("participants"), rs.getLong("total_minutes")));
    }

    public record AdminStatsRow(long totalStudents, long activeStudents, long totalProjects, long activeProjects,
                                 long approvedThisMonth, long totalServiceHoursThisYear) {
    }

    /**
     * The dashboard's headline figures, over <em>what this caller may see</em>
     * (issue 127).
     *
     * Every figure used to be school-wide, so a teacher scoped to two grades
     * read the same six numbers as the head teacher — measured identically on
     * the live server, down to the review average. A teacher's dashboard is for
     * running their own grades.
     *
     * Three different scope shapes, because the three tables reach a grade
     * differently: students carry it, service records reach it through their
     * student, and a project has no owning student at all — it is in scope when
     * one of the grades it is open to is.
     */
    public AdminStatsRow adminStats(LocalDate monthStart, LocalDate monthEndExclusive, LocalDate yearStart,
                                    LocalDate yearEnd, ScopeDescriptor scope) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("monthStart", monthStart.atStartOfDay())
                .addValue("monthEnd", monthEndExclusive.atStartOfDay())
                .addValue("yearStart", yearStart).addValue("yearEnd", yearEnd);
        String student = studentScopeClause(scope, "st", params);
        String record = recordScopeClause(scope, "sr", params);
        String project = projectScopeClause(scope, "p", params);

        String sql = """
                select
                    (select count(*) from student st where 1 = 1%1$s) as total_students,
                    (select count(*) from student st where st.status = 'ACTIVE'%1$s) as active_students,
                    (select count(*) from project p where 1 = 1%3$s) as total_projects,
                    (select count(*) from project p where p.status in ('PUBLISHED', 'REGISTRATION_OPEN', 'IN_PROGRESS')%3$s) as active_projects,
                    (select count(*) from service_record sr where sr.status = 'APPROVED'
                        and sr.updated_at >= :monthStart and sr.updated_at < :monthEnd%2$s) as approved_this_month,
                    (select coalesce(sum(sr.duration_minutes), 0) from service_record sr where sr.status = 'APPROVED'
                        and sr.service_date between :yearStart and :yearEnd%2$s) as year_minutes
                """.formatted(student, record, project);

        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new AdminStatsRow(
                rs.getLong("total_students"), rs.getLong("active_students"), rs.getLong("total_projects"),
                rs.getLong("active_projects"), rs.getLong("approved_this_month"), rs.getLong("year_minutes") / 60));
    }

    /**
     * The dashboard's "average review time", over the same scope.
     *
     * A separate method rather than a parameter on {@code reviewEfficiency}
     * because that one backs `GET /reports/review-efficiency`, whose shape is
     * in the contract; the dashboard needs one number, scoped, and nothing else
     * about that report changes.
     *
     * Carries the same stale-round guard {@link #reviewEfficiencyByReviewer}
     * explains at length — without it a returned-then-resubmitted record
     * contributes a negative duration.
     */
    public Double scopedOverallReviewAvgHours(LocalDate start, LocalDate end, ScopeDescriptor scope) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", start.atStartOfDay()).addValue("end", end.plusDays(1).atStartOfDay());
        String sql = """
                select avg(timestampdiff(second, sr.submitted_at, rd.created_at)) / 3600.0 as avg_hours
                from review_decision rd
                join service_record sr on sr.id = rd.service_record_id
                where rd.created_at >= :start and rd.created_at < :end and sr.submitted_at is not null
                  and rd.created_at >= sr.submitted_at%s
                """.formatted(recordScopeClause(scope, "sr", params));
        // Not `.stream().findFirst()`: `avg()` over no rows returns one row
        // holding null, and `Optional.of(null)` throws. Caught by
        // DashboardReportingIT, which has no decisions to average.
        List<Double> rows = jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            double value = rs.getDouble("avg_hours");
            return rs.wasNull() ? null : value;
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Grades a scope covers, for callers that filter per-grade figures rather than SQL rows. */
    public Set<String> scopeGradeIds(ScopeDescriptor scope) {
        return switch (scope) {
            case ScopeDescriptor.All ignored -> Set.of();
            case ScopeDescriptor.Grades grades -> grades.gradeIds();
            case ScopeDescriptor.Classes classes -> Set.copyOf(jdbcTemplate.queryForList(
                    "select distinct grade_id from class where id in (:classIds)",
                    new MapSqlParameterSource("classIds", nonEmpty(classes.classIds())), String.class));
            case ScopeDescriptor.Self ignored -> Set.of("__none__");
            case ScopeDescriptor.Projects ignored -> Set.of("__none__");
        };
    }

    private String studentScopeClause(ScopeDescriptor scope, String alias, MapSqlParameterSource params) {
        return switch (scope) {
            case ScopeDescriptor.All ignored -> "";
            case ScopeDescriptor.Grades grades -> {
                params.addValue("scopeGradeIds", nonEmpty(grades.gradeIds()));
                yield " and " + alias + ".grade_id in (:scopeGradeIds)";
            }
            case ScopeDescriptor.Classes classes -> {
                params.addValue("scopeClassIds", nonEmpty(classes.classIds()));
                yield " and " + alias + ".class_id in (:scopeClassIds)";
            }
            // A dashboard with these figures is never reached by a principal
            // scoped to itself or to projects, but "show them everything" is the
            // wrong way to be wrong about it.
            case ScopeDescriptor.Self ignored -> " and 1 = 0";
            case ScopeDescriptor.Projects ignored -> " and 1 = 0";
        };
    }

    /** A record is in scope when its student is. */
    private String recordScopeClause(ScopeDescriptor scope, String alias, MapSqlParameterSource params) {
        if (scope instanceof ScopeDescriptor.All) {
            return "";
        }
        return " and exists (select 1 from student st where st.id = " + alias + ".student_id"
                + studentScopeClause(scope, "st", params) + ")";
    }

    /**
     * A project is in scope when one of the grades it is open to is — projects
     * have no owning student, and `eligible_grade_ids` is a JSON array, so this
     * is an overlap rather than a column comparison.
     */
    private String projectScopeClause(ScopeDescriptor scope, String alias, MapSqlParameterSource params) {
        Set<String> gradeIds = scopeGradeIds(scope);
        if (scope instanceof ScopeDescriptor.All) {
            return "";
        }
        params.addValue("scopeGradeIdsJson", toJsonArray(gradeIds));
        return " and json_overlaps(" + alias + ".eligible_grade_ids, cast(:scopeGradeIdsJson as json))";
    }

    private String toJsonArray(Set<String> ids) {
        return nonEmpty(ids).stream()
                .map(id -> "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
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
