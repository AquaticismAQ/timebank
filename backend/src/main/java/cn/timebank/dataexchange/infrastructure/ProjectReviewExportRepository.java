package cn.timebank.dataexchange.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * The three exports whose rows are not students
 * (`Design/*&#47;12-DataExport.md` §6.4, §6.6, §6.7): Projects Report, Review
 * Efficiency and Review History.
 *
 * <p>Grouped together because they share the reviewer/role/bound-grades join
 * (F-20) and because none of them is a per-student query — the student scope
 * still applies to their <em>aggregates</em>, but not to whether a row exists.
 * A project runs whether or not the reader may see the students on it.</p>
 */
@Repository
public class ProjectReviewExportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ProjectReviewExportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ProjectRow(String id, String title, String summary, String category,
                              String organization, String location, String owner,
                              String eligibleGradeIds, String status,
                              LocalDateTime startAt, LocalDateTime endAt, Integer recognizedMinutes, Integer capacity,
                              int registrations, int participants, long approvedMinutes,
                              long approvedRecords, long submittedRecords) {
    }

    /**
     * @param averageReviewMinutes submission to decision; null when a reviewer has
     *                             decisions but none of their records carries a
     *                             submission time, which is not the same as zero.
     *                             Read via {@link Number} because MySQL's
     *                             {@code AVG()} is DECIMAL and arrives as a
     *                             {@code BigDecimal} — a {@code (Double)} cast
     *                             compiles and then throws at run time, but only
     *                             once some record actually has a submission time
     */
    public record ReviewerRow(String reviewerId, String reviewerName, String reviewerRole,
                               String boundGrades, long total, long approved, long rejected,
                               long returned, long escalated, Double averageReviewMinutes) {
    }

    public record ReviewHistoryRow(String id, String reviewerName, String reviewerRole, String boundGrades,
                                    String studentNumber, String studentName, LocalDate serviceDate,
                                    String subject, String decision, LocalDateTime decidedAt,
                                    String reasonCode, String comment,
                                    Integer durationMinutes, Integer creditedUnits, String recordStatus) {
    }

    /**
     * §6.4. One row per project that ran in the window.
     *
     * <p>The participation aggregates count only students the reader may see —
     * the student scope applies to the <em>numbers</em> even though it does not
     * decide whether the project row exists. A teacher therefore sees every
     * project, with the participation of their own students on it, which is the
     * reading that makes the file useful to them without disclosing who else
     * took part.</p>
     */
    public List<ProjectRow> projectsPage(String afterId, int limit, Set<String> studentIds,
                                          LocalDate yearFrom, LocalDate yearTo, String gradeFilter) {
        StringBuilder sql = new StringBuilder("""
                select p.id, p.title, p.summary, p.location, p.status, p.start_at, p.end_at,
                       p.recognized_minutes, p.capacity, p.eligible_grade_ids,
                       sc.name_en as category_en, sc.name_zh as category_zh,
                       o.name as organization_name, u.display_name as owner_name,
                       (select count(distinct e.student_id) from enrollment e
                         where e.project_id = p.id and e.status = 'CONFIRMED'
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("afterId", afterId == null ? "" : afterId)
                .addValue("limit", limit);
        appendStudentFilter(sql, params, studentIds, "e.student_id");
        sql.append(") as registrations,\n");

        sql.append("                       (select count(distinct sr.student_id) from service_record sr\n"
                + "                         where sr.project_id = p.id and sr.status = 'APPROVED'\n");
        appendStudentFilter(sql, params, studentIds, "sr.student_id");
        sql.append(") as participants,\n");

        sql.append("                       (select coalesce(sum(sr.duration_minutes), 0) from service_record sr\n"
                + "                         where sr.project_id = p.id and sr.status = 'APPROVED'\n");
        appendStudentFilter(sql, params, studentIds, "sr.student_id");
        sql.append(") as approved_minutes,\n");

        sql.append("                       (select count(*) from service_record sr\n"
                + "                         where sr.project_id = p.id and sr.status = 'APPROVED'\n");
        appendStudentFilter(sql, params, studentIds, "sr.student_id");
        sql.append(") as approved_records,\n");

        // Submitted, i.e. every non-draft record: `Approval Rate` is approved over
        // submitted **records**, so it measures how well service was documented,
        // not how many students turned up (§6.4).
        sql.append("                       (select count(*) from service_record sr\n"
                + "                         where sr.project_id = p.id and sr.status <> 'DRAFT'\n");
        appendStudentFilter(sql, params, studentIds, "sr.student_id");
        sql.append(") as submitted_records\n");

        sql.append("                from project p\n"
                + "                  left join service_category sc on sc.id = p.category_id\n"
                + "                  left join organization o on o.id = p.organization_id\n"
                + "                  left join user u on u.id = p.owner_id\n"
                + "                where p.id > :afterId\n");
        if (yearFrom != null && yearTo != null) {
            // Scheduled inside the year, matching /reports/top-projects' own rule.
            // The window is the stored one; `yearTo` is the year's last day, so the
            // exclusive upper bound is the start of the day after it.
            sql.append(" and p.start_at >= :yearFrom and p.start_at < :yearTo");
            params.addValue("yearFrom", yearFrom.atStartOfDay());
            params.addValue("yearTo", yearTo.plusDays(1).atStartOfDay());
        }
        if (gradeFilter != null) {
            // eligible_grade_ids is a JSON array, so this is a JSON membership
            // test: FIND_IN_SET would silently never match, and a LIKE would match
            // grade_1 inside grade_10. Q-7: a project with no eligible grades is
            // open to every grade and therefore matches any filter.
            sql.append(" and (p.eligible_grade_ids is null or p.eligible_grade_ids = ''"
                    + " or json_contains(p.eligible_grade_ids, json_quote(:gradeFilter)))");
            params.addValue("gradeFilter", gradeFilter);
        }
        sql.append(" order by p.id asc limit :limit");

        return jdbcTemplate.query(sql.toString(), params, (rs, i) -> new ProjectRow(
                rs.getString("id"), rs.getString("title"), rs.getString("summary"),
                categoryLabel(rs.getString("category_en"), rs.getString("category_zh")),
                rs.getString("organization_name"), rs.getString("location"), rs.getString("owner_name"),
                rs.getString("eligible_grade_ids"), rs.getString("status"),
                rs.getObject("start_at", LocalDateTime.class), rs.getObject("end_at", LocalDateTime.class),
                (Integer) rs.getObject("recognized_minutes"), (Integer) rs.getObject("capacity"),
                rs.getInt("registrations"), rs.getInt("participants"),
                rs.getLong("approved_minutes"), rs.getLong("approved_records"),
                rs.getLong("submitted_records")));
    }

    /**
     * §6.6. One row per reviewer, with counts <b>and</b> rates.
     *
     * <p>The counts are not decoration: one review, approved, is an approval rate
     * of 100.00%, which in a column of percentages looks much like 400 reviews at
     * 98%. Giving both is the whole change §6.6 made to the draft.</p>
     *
     * <p>Q-3: {@code ESCALATE} is a real decision, so it counts toward
     * {@code Total Reviews}, but it is not an outcome and is excluded from all
     * three rates. The rates therefore do <b>not</b> sum to 100% for a reviewer
     * who escalates. Omitting escalations instead would hide real decisions from
     * a file called Review History, which is worse — so the card text says the
     * rates do not add up rather than leaving a reader to discover it.</p>
     */
    public List<ReviewerRow> reviewerEfficiency(LocalDate yearFrom, LocalDate yearTo, Set<String> studentIds) {
        StringBuilder sql = new StringBuilder("""
                select rd.reviewer_id,
                       u.display_name as reviewer_name,
                       r.code as reviewer_role,
                       -- name_en, not code: `grade.code` is the id again
                       -- (`grade_8`), and this column is read by a person.
                       (select group_concat(distinct g.name_en order by g.sequence separator ', ')
                          from teacher_scope_assignment tsa
                          join grade g on g.id = tsa.scope_value
                         where tsa.user_id = rd.reviewer_id and tsa.scope_type = 'GRADE') as bound_grades,
                       count(*) as total,
                       sum(rd.decision = 'APPROVE') as approved,
                       sum(rd.decision = 'REJECT') as rejected,
                       sum(rd.decision = 'RETURN_FOR_CHANGES') as returned,
                       sum(rd.decision = 'ESCALATE') as escalated,
                       avg(timestampdiff(minute, sr.submitted_at, rd.created_at)) as avg_minutes
                from review_decision rd
                  join service_record sr on sr.id = rd.service_record_id
                  left join user u on u.id = rd.reviewer_id
                  left join user_role ur on ur.user_id = rd.reviewer_id
                  left join role r on r.id = ur.role_id
                where 1 = 1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendDecisionYear(sql, params, yearFrom, yearTo);
        appendStudentFilter(sql, params, studentIds, "sr.student_id");
        sql.append(" group by rd.reviewer_id, u.display_name, r.code order by total desc, u.display_name asc");

        return jdbcTemplate.query(sql.toString(), params, (rs, i) -> new ReviewerRow(
                rs.getString("reviewer_id"), rs.getString("reviewer_name"), rs.getString("reviewer_role"),
                rs.getString("bound_grades"), rs.getLong("total"), rs.getLong("approved"),
                rs.getLong("rejected"), rs.getLong("returned"), rs.getLong("escalated"),
                asDouble(rs.getObject("avg_minutes"))));
    }

    /**
     * §6.7. One row per review decision.
     *
     * <p>{@code reviewerId} is enforced here rather than merely defaulted in the
     * UI (F-17): a teacher may see only their own decisions, and a filter that
     * lived only in a dropdown would be bypassed by anyone willing to edit a
     * request body.</p>
     */
    public List<ReviewHistoryRow> reviewHistoryPage(String afterId, int limit,
                                                     LocalDate yearFrom, LocalDate yearTo,
                                                     String reviewerId, Set<String> studentIds) {
        StringBuilder sql = new StringBuilder("""
                select rd.id, rd.decision, rd.created_at, rd.reason_code, rd.comment,
                       u.display_name as reviewer_name, r.code as reviewer_role,
                       (select group_concat(distinct g.name_en order by g.sequence separator ', ')
                          from teacher_scope_assignment tsa
                          join grade g on g.id = tsa.scope_value
                         where tsa.user_id = rd.reviewer_id and tsa.scope_type = 'GRADE') as bound_grades,
                       s.student_number, s.display_name as student_name,
                       sr.service_date, sr.duration_minutes, sr.credited_units, sr.status as record_status,
                       p.title as project_title, sr.organization_name
                from review_decision rd
                  join service_record sr on sr.id = rd.service_record_id
                  join student s on s.id = sr.student_id
                  left join user u on u.id = rd.reviewer_id
                  left join user_role ur on ur.user_id = rd.reviewer_id
                  left join role r on r.id = ur.role_id
                  left join project p on p.id = sr.project_id
                where rd.id > :afterId
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("afterId", afterId == null ? "" : afterId)
                .addValue("limit", limit);
        if (reviewerId != null) {
            sql.append(" and rd.reviewer_id = :reviewerId");
            params.addValue("reviewerId", reviewerId);
        }
        appendDecisionYear(sql, params, yearFrom, yearTo);
        appendStudentFilter(sql, params, studentIds, "sr.student_id");
        sql.append(" order by rd.id asc limit :limit");

        return jdbcTemplate.query(sql.toString(), params, (rs, i) -> new ReviewHistoryRow(
                rs.getString("id"), rs.getString("reviewer_name"), rs.getString("reviewer_role"),
                rs.getString("bound_grades"), rs.getString("student_number"), rs.getString("student_name"),
                rs.getObject("service_date", LocalDate.class),
                subjectLabel(rs.getString("project_title"), rs.getString("organization_name")),
                rs.getString("decision"), rs.getObject("created_at", LocalDateTime.class),
                rs.getString("reason_code"), rs.getString("comment"),
                (Integer) rs.getObject("duration_minutes"), (Integer) rs.getObject("credited_units"),
                rs.getString("record_status")));
    }

    /** A decision belongs to the year it was <em>taken</em> in, not the year the service happened (§4). */
    private void appendDecisionYear(StringBuilder sql, MapSqlParameterSource params,
                                     LocalDate yearFrom, LocalDate yearTo) {
        if (yearFrom != null && yearTo != null) {
            sql.append(" and rd.created_at >= :decisionFrom and rd.created_at < :decisionTo");
            params.addValue("decisionFrom", yearFrom.atStartOfDay());
            params.addValue("decisionTo", yearTo.plusDays(1).atStartOfDay());
        }
    }

    /**
     * Narrows an aggregate to the students the reader may see.
     *
     * <p>An <b>empty</b> set becomes {@code and 1 = 0} rather than {@code in ()},
     * which is a MySQL syntax error. It is a real case here in a way it is not for
     * the per-student exports: a grade filter that matches no students still has
     * projects to list, and the right answer is every project with zero
     * participation — not a failed job, and not the whole school's figures.</p>
     */
    private void appendStudentFilter(StringBuilder sql, MapSqlParameterSource params,
                                      Set<String> studentIds, String column) {
        if (studentIds == null) {
            return;
        }
        if (studentIds.isEmpty()) {
            sql.append(" and 1 = 0");
            return;
        }
        sql.append(" and ").append(column).append(" in (:studentIds)");
        params.addValue("studentIds", studentIds);
    }

    private static String categoryLabel(String english, String chinese) {
        return english != null && !english.isBlank() ? english : chinese;
    }

    private static String subjectLabel(String projectTitle, String organizationName) {
        return projectTitle != null && !projectTitle.isBlank() ? projectTitle : organizationName;
    }

    /** MySQL's AVG() is DECIMAL; accept any Number so the driver's choice of type is not a run-time trap. */
    private static Double asDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
