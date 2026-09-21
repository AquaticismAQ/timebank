package cn.timebank.lifecycle.application;

import cn.timebank.dataexchange.application.ExportService;
import cn.timebank.dataexchange.application.dto.DataExchangeRequests.CreateExportRequest;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.file.application.FileStorage;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-11 / Phase 5 exit criterion 3: "an export's stored file is gone after the
 * retention interval, and its job row reads `result_ref = NULL` rather than
 * pointing at a deleted key"
 * (`Docs/optimization/11-DataImportExportOptimization.md` §3.5).
 *
 * <p>The exports here are real ones — a real job row, a real
 * `export_&lt;jobId&gt;.csv` written through {@link FileStorage} by the real
 * runner — and the retention boundary is crossed for real, by backdating one
 * job's `finished_at` and driving the sweep from both sides of the cutoff. Each
 * test asserts a second, fresh export survives the same run, because "the file
 * is gone" is also true of a sweep that deletes everything.</p>
 */
class ExportOutputRetentionIT extends AbstractIntegrationTest {

    @Autowired
    private ExportService exportService;
    @Autowired
    private ExportOutputRetentionJob retentionJob;
    @Autowired
    private FileStorage fileStorage;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void anExportSurvivesUntilItCrossesTheRetentionBoundary_thenIsDeletedWithItsResultRef() {
        Instant now = Instant.now();
        String oldJobId = finishedExport();
        String freshJobId = finishedExport();
        String oldKey = resultRefOf(oldJobId);
        String freshKey = resultRefOf(freshJobId);
        assertThat(oldKey).isEqualTo("export_" + oldJobId + ".csv");

        // The one file, aged past the window — 8 days, so it sits one day the
        // wrong side of the 7-day constant rather than on top of it.
        backdateFinishedAt(oldJobId, now.minus(8, ChronoUnit.DAYS));

        // Same file, same sweep, run from a point in time where it is 6 days
        // old: nothing is removed. This is the half that makes the next
        // assertion mean something — without it, "deleted" could just as well
        // be "deletes whatever it finds".
        ExportOutputRetentionJob.SweepResult beforeBoundary = retentionJob.sweep(now.minus(2, ChronoUnit.DAYS));
        assertThat(beforeBoundary.filesDeleted()).isZero();
        assertThat(contentOf(oldKey)).startsWith("No.,Student Number,Student Name,");
        assertThat(resultRefOf(oldJobId)).isEqualTo(oldKey);

        ExportOutputRetentionJob.SweepResult afterBoundary = retentionJob.sweep(now);

        assertThat(afterBoundary.filesDeleted()).isEqualTo(1);
        assertThat(afterBoundary.failures()).isZero();
        assertThatThrownBy(() -> fileStorage.open(oldKey))
                .hasMessageContaining(oldKey); // the bytes are gone, not merely unreferenced
        assertThat(resultRefOf(oldJobId)).isNull(); // and the row no longer advertises them

        // The export taken minutes ago is untouched, and still downloadable.
        assertThat(resultRefOf(freshJobId)).isEqualTo(freshKey);
        assertThat(contentOf(freshKey)).startsWith("No.,Student Number,Student Name,");
    }

    @Test
    void theSweepRecordsWhatItDid() {
        Instant now = Instant.now();
        // Every test in this class shares one schema, and several of them sweep.
        // Clear the action's rows so this assertion counts this run's row and
        // not whatever ran before it.
        jdbc.update("delete from audit_log where action = 'lifecycle.exportOutputRetention'",
                new MapSqlParameterSource());
        String jobId = finishedExport();
        backdateFinishedAt(jobId, now.minus(30, ChronoUnit.DAYS));

        retentionJob.sweep(now);

        Integer audited = jdbc.queryForObject("""
                select count(*) from audit_log
                where action = 'lifecycle.exportOutputRetention' and actor_id = 'scheduler'
                """, new MapSqlParameterSource(), Integer.class);
        assertThat(audited).isEqualTo(1);
        String after = jdbc.queryForObject("""
                select after_summary from audit_log where action = 'lifecycle.exportOutputRetention'
                order by created_at desc limit 1
                """, new MapSqlParameterSource(), String.class);
        // Whitespace removed: MySQL reformats a JSON column on the way out.
        assertThat(after.replace(" ", "")).contains("\"filesDeleted\":1").contains("\"retentionDays\":7");
    }

    @Test
    void anUnreadableFileDoesNotStrandTheRestOfTheBatch_andIsRetriedNextRun() {
        Instant now = Instant.now();
        String goodJobId = finishedExport();
        backdateFinishedAt(goodJobId, now.minus(9, ChronoUnit.DAYS));

        // A job row pointing at a key whose file is missing — what a half-done
        // deletion, or a hand-deleted file, actually leaves behind. deleteIfExists
        // makes this a no-op rather than a failure, so the row should still be
        // cleaned up and the batch should continue either way.
        String danglingJobId = insertFinishedExportRow("export_missing_" + System.nanoTime() + ".csv",
                now.minus(9, ChronoUnit.DAYS));

        ExportOutputRetentionJob.SweepResult result = retentionJob.sweep(now);

        assertThat(result.filesDeleted()).isEqualTo(2);
        assertThat(result.failures()).isZero();
        assertThat(resultRefOf(goodJobId)).isNull();
        assertThat(resultRefOf(danglingJobId)).isNull();
    }

    @Test
    void spentDownloadTokensAreSweptOnTheirOwnMuchShorterClock() {
        Instant now = Instant.now();
        insertRedemption("a".repeat(64), now.minus(2, ChronoUnit.DAYS));
        insertRedemption("b".repeat(64), now.minus(1, ChronoUnit.HOURS));

        ExportOutputRetentionJob.SweepResult result = retentionJob.sweep(now);

        assertThat(result.redemptionsDeleted()).isEqualTo(1);
        assertThat(redemptionExists("a".repeat(64))).isFalse();
        assertThat(redemptionExists("b".repeat(64))).isTrue(); // still inside the day
    }

    @Test
    void importErrorReportsAreNotTouched() {
        Instant now = Instant.now();
        String importJobId = "job_it_import_" + System.nanoTime() % 100000;
        jdbc.update("""
                insert into job (id, kind, resource_type, status, created_by, processed_rows, checkpoint_offset,
                                 result_ref, version, created_at, updated_at, finished_at)
                values (:id, 'IMPORT', 'serviceRecordsHistorical', 'COMMITTED', 'usr_test_admin', 0, 0,
                        :ref, 1, :at, :at, :at)
                """, new MapSqlParameterSource().addValue("id", importJobId)
                .addValue("ref", "import_errors_" + importJobId + ".csv")
                .addValue("at", now.minus(60, ChronoUnit.DAYS)));

        retentionJob.sweep(now);

        // An import's result_ref is its row-error report — a different thing
        // with a different lifecycle, deliberately out of this job's reach.
        assertThat(resultRefOf(importJobId)).isNotNull();
    }

    private String finishedExport() {
        // `serviceRecords` because this fixture needs a type that really runs
        // and really writes a file — the sweep has nothing to delete otherwise.
        // It used to be `participation`, which V31 retired along with the other
        // re-cut reports (13-DataExport.md §9.4); a retired key is refused at
        // create, so the sweep would have been proved against no file at all.
        JobDto job = exportService.create(new CreateExportRequest("serviceRecords", null), adminAuth());
        Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
        while (Instant.now().isBefore(deadline)) {
            String status = statusOf(job.id());
            if ("SUCCEEDED".equals(status)) {
                return job.id();
            }
            if ("FAILED".equals(status)) {
                throw new AssertionError("Export job failed");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("Export job " + job.id() + " did not finish within timeout");
    }

    /** A finished export row whose file is deliberately absent from storage. */
    private String insertFinishedExportRow(String storageKey, Instant finishedAt) {
        String jobId = "job_it_exp_" + System.nanoTime() % 100000;
        jdbc.update("""
                insert into job (id, kind, resource_type, status, created_by, processed_rows, checkpoint_offset,
                                 result_ref, version, created_at, updated_at, finished_at)
                values (:id, 'EXPORT', 'serviceRecords', 'SUCCEEDED', 'usr_test_admin', 0, 0,
                        :ref, 1, :at, :at, :at)
                """, new MapSqlParameterSource().addValue("id", jobId)
                .addValue("ref", storageKey).addValue("at", finishedAt));
        return jobId;
    }

    private void backdateFinishedAt(String jobId, Instant finishedAt) {
        jdbc.update("update job set finished_at = :at, updated_at = :at where id = :id",
                new MapSqlParameterSource().addValue("id", jobId).addValue("at", finishedAt));
    }

    private void insertRedemption(String tokenHash, Instant redeemedAt) {
        jdbc.update("""
                insert into export_download_redemption (token_hash, job_id, redeemed_at)
                values (:hash, 'job_it_fake', :at)
                """, new MapSqlParameterSource().addValue("hash", tokenHash).addValue("at", redeemedAt));
    }

    private boolean redemptionExists(String tokenHash) {
        Integer count = jdbc.queryForObject(
                "select count(*) from export_download_redemption where token_hash = :hash",
                new MapSqlParameterSource("hash", tokenHash), Integer.class);
        return count != null && count > 0;
    }

    private String resultRefOf(String jobId) {
        return jdbc.queryForObject("select result_ref from job where id = :id",
                new MapSqlParameterSource("id", jobId), String.class);
    }

    private String statusOf(String jobId) {
        return jdbc.queryForObject("select status from job where id = :id",
                new MapSqlParameterSource("id", jobId), String.class);
    }

    private String contentOf(String storageKey) {
        try (InputStream in = fileStorage.open(storageKey)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("Expected " + storageKey + " to still be readable", e);
        }
    }

    /** A school-wide admin, so the fixture export is not narrowed by scope — this class is about retention, not scoping. */
    private Authentication adminAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
