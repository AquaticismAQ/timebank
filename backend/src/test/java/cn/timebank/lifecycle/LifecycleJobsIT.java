package cn.timebank.lifecycle;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountTransactionDto;
import cn.timebank.common.OpaqueIds;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.lifecycle.application.CohortPurgeService;
import cn.timebank.lifecycle.application.LedgerReconciliationService;
import cn.timebank.lifecycle.application.dto.ReconciliationRunResultDto;
import cn.timebank.lifecycle.infrastructure.LogPartitionRepository;
import cn.timebank.lifecycle.infrastructure.PurgeRunRepository;
import cn.timebank.lifecycle.infrastructure.ReconciliationRunRepository;
import cn.timebank.lifecycle.infrastructure.ShortLivedTableRepository;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6.1 (log partition lifecycle), B6.2 (idempotency sweep), and B6.7
 * (ledger reconciliation) exit criteria against real MySQL: partition
 * pruning is an instant `DROP PARTITION` that removes exactly the targeted
 * rows and nothing else, the idempotency sweep age-deletes correctly, and
 * reconciliation detects and names a deliberately injected ledger drift.
 */
class LifecycleJobsIT extends AbstractIntegrationTest {

    @Autowired
    private LogPartitionRepository logPartitionRepository;
    @Autowired
    private ShortLivedTableRepository shortLivedTableRepository;
    @Autowired
    private LedgerReconciliationService reconciliationService;
    @Autowired
    private StudentService studentService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Autowired
    private CohortPurgeService cohortPurgeService;
    @Autowired
    private PurgeRunRepository purgeRunRepository;
    @Autowired
    private ReconciliationRunRepository reconciliationRunRepository;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void ensuringAFuturePartitionAddsExactlyOneNamedPartition() {
        YearMonth farFuture = YearMonth.now().plusYears(5); // well beyond the pre-seeded window either way
        List<String> before = logPartitionRepository.namedPartitions("audit_log");
        assertThat(before).doesNotContain("p_" + farFuture.toString().replace("-", "_"));

        logPartitionRepository.ensurePartitionExists("audit_log", farFuture, before);

        List<String> after = logPartitionRepository.namedPartitions("audit_log");
        assertThat(after).contains("p_" + farFuture.toString().replace("-", "_"));
        assertThat(after.size()).isEqualTo(before.size() + 1);
    }

    @Test
    void droppingAPartitionRemovesExactlyItsRowsAndNoOthers() {
        // p_2026_08 covers August 2026 (today); p_2026_09 covers September —
        // both pre-seeded by V1/V3, both real named partitions, ordered.
        insertAuditRow("aug_row", Instant.parse("2026-08-15T00:00:00Z"));
        insertAuditRow("sep_row", Instant.parse("2026-09-15T00:00:00Z"));
        assertThat(countAuditRowsWithAction("aug_row")).isEqualTo(1);
        assertThat(countAuditRowsWithAction("sep_row")).isEqualTo(1);

        logPartitionRepository.dropPartition("audit_log", "p_2026_08");

        assertThat(countAuditRowsWithAction("aug_row")).isZero();
        assertThat(countAuditRowsWithAction("sep_row")).isEqualTo(1); // untouched
        assertThat(logPartitionRepository.namedPartitions("audit_log")).doesNotContain("p_2026_08");
    }

    @Test
    void idempotencySweepDeletesOnlyRecordsOlderThanTheCutoff() {
        insertIdempotencyRecord("old_key", Instant.now().minus(10, ChronoUnit.DAYS));
        insertIdempotencyRecord("recent_key", Instant.now().minus(1, ChronoUnit.DAYS));

        int deleted = shortLivedTableRepository.deleteIdempotencyRecordsOlderThan(Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1);
        assertThat(countIdempotencyRecords("old_key")).isZero();
        assertThat(countIdempotencyRecords("recent_key")).isEqualTo(1);
    }

    @Test
    void reconciliationDetectsAndNamesADeliberatelyInjectedDrift() {
        StudentDetailDto student = createStudent();
        AccountTransactionDto credit = accountService.credit(student.id(), student.gradeId(), student.classId(),
                500, "SERVICE_RECORD", "sr_test", "GOOD", "usr_test_admin");
        String accountId = credit.accountId();

        // Deliberate drift: corrupt the stored balance directly, bypassing AccountService entirely.
        jdbcTemplate.update("update account set balance = balance + 999 where id = :id",
                new MapSqlParameterSource("id", accountId));

        ReconciliationRunResultDto result = reconciliationService.runFull();

        assertThat(result.mismatches()).extracting(ReconciliationRunResultDto.Mismatch::accountId).contains(accountId);
        var mismatch = result.mismatches().stream().filter(m -> m.accountId().equals(accountId)).findFirst().orElseThrow();
        assertThat(mismatch.storedBalance()).isEqualTo(500 + 999);
        assertThat(mismatch.computedBalance()).isEqualTo(500);

        // A clean account (no drift) must not be reported.
        StudentDetailDto cleanStudent = createStudent();
        AccountTransactionDto cleanCredit = accountService.credit(cleanStudent.id(), cleanStudent.gradeId(), cleanStudent.classId(),
                200, "SERVICE_RECORD", "sr_test2", "GOOD", "usr_test_admin");

        ReconciliationRunResultDto secondRun = reconciliationService.runFull();

        assertThat(secondRun.mismatches()).extracting(ReconciliationRunResultDto.Mismatch::accountId)
                .contains(accountId)
                .doesNotContain(cleanCredit.accountId());
    }

    @Test
    void checkpointModeOnlyChecksAccountsTouchedSinceTheLastCheckpoint() {
        ReconciliationRunResultDto firstCheckpoint = reconciliationService.runCheckpoint();
        assertThat(firstCheckpoint.mode()).isEqualTo("CHECKPOINT");

        StudentDetailDto student = createStudent();
        accountService.credit(student.id(), student.gradeId(), student.classId(),
                100, "SERVICE_RECORD", "sr_test3", "GOOD", "usr_test_admin");

        ReconciliationRunResultDto secondCheckpoint = reconciliationService.runCheckpoint();
        assertThat(secondCheckpoint.accountsChecked()).isGreaterThanOrEqualTo(1);
        assertThat(secondCheckpoint.mismatches()).isEmpty();
    }

    @Test
    void nonExecutionWatchdogDetectsAHealthyRunAndAStaleOne() {
        cohortPurgeService.run(true, "usr_test_admin");
        reconciliationService.runCheckpoint();

        // Checked from "now" backward, a run that just completed is within any reasonable window.
        assertThat(purgeRunRepository.hasSucceededSince(Instant.now().minus(1, ChronoUnit.HOURS))).isTrue();
        assertThat(reconciliationRunRepository.hasSucceededSince(Instant.now().minus(1, ChronoUnit.HOURS))).isTrue();

        // Checked against a window that starts in the future, no run (however recent) can satisfy it —
        // the same shape as "hasn't run in 36 hours" without needing to wait 36 real hours.
        assertThat(purgeRunRepository.hasSucceededSince(Instant.now().plusSeconds(10))).isFalse();
        assertThat(reconciliationRunRepository.hasSucceededSince(Instant.now().plusSeconds(10))).isFalse();
    }

    private void insertAuditRow(String action, Instant createdAt) {
        jdbcTemplate.update("""
                insert into audit_log (id, actor_id, action, resource_type, resource_id, created_at)
                values (:id, 'usr_test_admin', :action, 'test', 'res_test', :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", OpaqueIds.generate("aud")).addValue("action", action).addValue("createdAt", createdAt));
    }

    private int countAuditRowsWithAction(String action) {
        Long c = jdbcTemplate.queryForObject("select count(*) from audit_log where action = :action",
                new MapSqlParameterSource("action", action), Long.class);
        return c == null ? 0 : c.intValue();
    }

    private void insertIdempotencyRecord(String key, Instant updatedAt) {
        jdbcTemplate.update("""
                insert into idempotency_record (id, idempotency_key, principal_id, endpoint, request_fingerprint, status, created_at, updated_at)
                values (:id, :key, 'usr_test_admin', 'TEST', 'fp', 'COMPLETED', :updatedAt, :updatedAt)
                """, new MapSqlParameterSource()
                .addValue("id", OpaqueIds.generate("idem")).addValue("key", key).addValue("updatedAt", updatedAt));
    }

    private int countIdempotencyRecords(String key) {
        Long c = jdbcTemplate.queryForObject("select count(*) from idempotency_record where idempotency_key = :key",
                new MapSqlParameterSource("key", key), Long.class);
        return c == null ? 0 : c.intValue();
    }

    private StudentDetailDto createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_lifecycle_" + n, "Lifecycle Student " + n, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1));
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }
}
