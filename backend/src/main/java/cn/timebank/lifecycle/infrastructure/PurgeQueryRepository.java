package cn.timebank.lifecycle.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B6.3's cohort purge, expressed as raw SQL in a fixed, FK-respecting
 * child-before-parent order (verified against the live schema's actual
 * foreign keys, not assumed). One student per transaction (the caller
 * wraps each {@link #deleteAllForStudent} call in its own transaction) —
 * bounds the blast radius of any one failure to a single student rather
 * than the whole cohort.
 *
 * {@code event_proposal}/{@code project_student_organizer} are purged too
 * even though `07-Backend-B5-AdminSurface.md`'s deliverable list for the
 * purge doesn't name them explicitly — `event_proposal.proposer_student_id`
 * and `project_student_organizer.student_id` both carry real foreign keys
 * to `student`, so leaving them out would make the purge fail outright for
 * any graduated student who ever authored a proposal or organized a
 * project. Treated as a required completeness fix, not a scope expansion.
 */
@Repository
public class PurgeQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PurgeQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Graduation is treated as the end of the academic year named by
     * {@code graduation_year} (Aug 31 of that year, matching the seeded
     * academic-year calendar) — eligible for purge one year after that.
     * A deliberate, documented interpretation of "graduation + 1 year"
     * (Design.md §16.3); no exact day was pinned down at the design stage.
     */
    public List<Integer> findEligibleGraduationYears(LocalDate asOf) {
        String sql = """
                select distinct graduation_year from student
                where DATE(CONCAT(graduation_year + 1, '-08-31')) <= :asOf
                order by graduation_year
                """;
        return jdbcTemplate.queryForList(sql, new MapSqlParameterSource("asOf", asOf), Integer.class);
    }

    public List<String> findEligibleStudentIds(int graduationYear, LocalDate asOf) {
        String sql = """
                select id from student
                where graduation_year = :year and DATE(CONCAT(graduation_year + 1, '-08-31')) <= :asOf
                """;
        return jdbcTemplate.queryForList(sql, new MapSqlParameterSource()
                .addValue("year", graduationYear).addValue("asOf", asOf), String.class);
    }

    public record StudentRowCounts(int serviceRecords, int accountTransactions, int cases, int enrollments,
                                    int proposals, int notifications, List<String> fileIds, String linkedUserId) {
    }

    public StudentRowCounts countForStudent(String studentId) {
        MapSqlParameterSource params = new MapSqlParameterSource("studentId", studentId);
        int serviceRecords = count("select count(*) from service_record where student_id = :studentId", params);
        int accountTransactions = count("""
                select count(*) from account_transaction
                where account_id = (select id from account where student_id = :studentId)
                """, params);
        int cases = count("select count(*) from case_record where student_id = :studentId", params);
        int enrollments = count("select count(*) from enrollment where student_id = :studentId", params);
        int proposals = count("select count(*) from event_proposal where proposer_student_id = :studentId", params);
        String linkedUserId = jdbcTemplate.query("select id from user where student_id = :studentId", params,
                        (rs, rowNum) -> rs.getString("id"))
                .stream().findFirst().orElse(null);
        int notifications = linkedUserId == null ? 0 : count(
                "select count(*) from notification where recipient_user_id = :userId",
                new MapSqlParameterSource("userId", linkedUserId));
        List<String> fileIds = findFileIdsForStudent(studentId);
        return new StudentRowCounts(serviceRecords, accountTransactions, cases, enrollments, proposals,
                notifications, fileIds, linkedUserId);
    }

    private List<String> findFileIdsForStudent(String studentId) {
        List<String> jsonArrays = jdbcTemplate.queryForList(
                "select evidence_file_ids from service_record where student_id = :studentId and evidence_file_ids is not null",
                new MapSqlParameterSource("studentId", studentId), String.class);
        return jsonArrays.stream()
                .flatMap(json -> java.util.Arrays.stream(json.replaceAll("[\\[\\]\"]", "").split(",")))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** @return total rows deleted across every table (not including evidence objects, which the caller purges separately through {@code FileService}). */
    public int deleteAllForStudent(String studentId) {
        MapSqlParameterSource p = new MapSqlParameterSource("studentId", studentId);
        int total = 0;

        total += update("delete from review_decision where service_record_id in " +
                "(select id from service_record where student_id = :studentId)", p);
        total += update("delete from service_record_revision where service_record_id in " +
                "(select id from service_record where student_id = :studentId)", p);

        update("update account_transaction set reversal_of_transaction_id = null where account_id = " +
                "(select id from account where student_id = :studentId)", p);
        total += update("delete from account_transaction where account_id = " +
                "(select id from account where student_id = :studentId)", p);
        total += update("delete from account where student_id = :studentId", p);

        total += update("delete from case_decision where case_id in " +
                "(select id from case_record where student_id = :studentId)", p);
        total += update("delete from case_revision where case_id in " +
                "(select id from case_record where student_id = :studentId)", p);
        total += update("delete from case_record where student_id = :studentId", p);

        total += update("delete from event_proposal_review where event_proposal_id in " +
                "(select id from event_proposal where proposer_student_id = :studentId)", p);
        total += update("delete from event_proposal_revision where event_proposal_id in " +
                "(select id from event_proposal where proposer_student_id = :studentId)", p);
        total += update("delete from event_proposal where proposer_student_id = :studentId", p);

        total += update("delete from project_student_organizer where student_id = :studentId", p);
        total += update("delete from enrollment where student_id = :studentId", p);
        total += update("delete from service_record where student_id = :studentId", p);
        total += update("delete from student_progress where student_id = :studentId", p);

        String linkedUserId = jdbcTemplate.query("select id from user where student_id = :studentId", p,
                        (rs, rowNum) -> rs.getString("id"))
                .stream().findFirst().orElse(null);
        if (linkedUserId != null && isStudentOnlyAccount(linkedUserId)) {
            MapSqlParameterSource up = new MapSqlParameterSource("userId", linkedUserId);
            total += update("delete from notification where recipient_user_id = :userId", up);
            total += update("delete from user_role where user_id = :userId", up);
            // Issue 090. `activation_code` (V27) holds two FKs onto `user`, and
            // rows survive redemption, so a graduated student almost always has
            // at least one — without this the purge fails on
            // `fk_activation_code_user` at the `delete from user` below.
            // Deliberately only `user_id`: `issued_by` names the *admin* who
            // issued the slip, so deleting by it would destroy other, current
            // students' codes. No staff purge path exists today; see issue 090 §4.
            total += update("delete from activation_code where user_id = :userId", up);
            // No `password_reset_token` delete: the table is gone (V28, issue
            // 089) along with the self-service recovery flow it backed. This
            // line had to go in the same change as the drop — it is raw SQL, so
            // nothing would have failed to compile, and the first symptom would
            // have been a purge blowing up against a table that no longer
            // exists.
            total += update("delete from teacher_scope_assignment where user_id = :userId", up);
            total += update("delete from user where id = :userId", up);
        }

        total += update("delete from student where id = :studentId", p);
        return total;
    }

    /** Never delete a user row that also holds a staff role — only ever true for a mistaken dual-role assignment, but a purge is exactly the wrong place to find that out the hard way. */
    private boolean isStudentOnlyAccount(String userId) {
        List<String> roleCodes = jdbcTemplate.query("""
                select r.code from user_role ur join role r on r.id = ur.role_id where ur.user_id = :userId
                """, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> rs.getString("code"));
        return Set.copyOf(roleCodes).stream().allMatch(code -> code.equals("student"));
    }

    private int count(String sql, MapSqlParameterSource params) {
        Long result = jdbcTemplate.queryForObject(sql, params, Long.class);
        return result == null ? 0 : result.intValue();
    }

    private int update(String sql, MapSqlParameterSource params) {
        return jdbcTemplate.update(sql, params);
    }
}
