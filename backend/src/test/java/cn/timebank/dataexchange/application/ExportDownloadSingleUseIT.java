package cn.timebank.dataexchange.application;

import cn.timebank.common.error.ApiErrorCode;
import cn.timebank.common.error.ApiException;
import cn.timebank.dataexchange.application.dto.DataExchangeRequests.CreateExportRequest;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.dataexchange.infrastructure.JobRepository;
import cn.timebank.file.application.dto.DownloadResult;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-10 / Phase 5 exit criterion 2: "a download URL fetched twice succeeds once
 * and is refused the second time" (`Docs/optimization/11-DataImportExportOptimization.md`
 * §3.5). Against real MySQL, because the property is enforced by a primary key,
 * not by anything that would show up in a mocked test.
 *
 * <p>Every test here redeems the <b>identical</b> token twice. A test that
 * redeemed once could not tell single-use from unlimited, which was the state
 * of the world before V33.</p>
 */
class ExportDownloadSingleUseIT extends AbstractIntegrationTest {

    @Autowired
    private ExportService exportService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void aDownloadLinkWorksOnceAndIsRefusedOnEveryLaterUse() throws Exception {
        String token = issueTokenForAFinishedExport();

        DownloadResult first = exportService.download(token);
        assertThat(new String(first.content().readAllBytes(), StandardCharsets.UTF_8))
                .startsWith("gradeId,gradeName,");

        assertThatThrownBy(() -> exportService.download(token))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been used")
                // Not the expiry message: the link in the administrator's hand is
                // perfectly fresh, and telling them it expired would send them
                // looking at a clock that has nothing to do with the refusal.
                .hasMessageNotContaining("expired")
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.STATE_CONFLICT);

        // And a third attempt is refused the same way, not silently allowed
        // once the "recently redeemed" state is a moment older.
        assertThatThrownBy(() -> exportService.download(token))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void spendingOneLinkDoesNotBreakTheExportForTheNextLink() throws Exception {
        JobDto export = finishedExport();
        String first = tokenOf(exportService.issueDownloadUrl(export.id(), adminAuth()));
        String second = tokenOf(exportService.issueDownloadUrl(export.id(), adminAuth()));
        assertThat(second).isNotEqualTo(first); // different expiry second, so a genuinely different token

        exportService.download(first).content().close();
        assertThatThrownBy(() -> exportService.download(first)).hasMessageContaining("already been used");

        // The refusal is of the spent *link*, not of the export: re-issuing is
        // the documented way back (§2.11 rejected deleting the file on first
        // download precisely so this stays possible).
        DownloadResult viaSecondLink = exportService.download(second);
        assertThat(new String(viaSecondLink.content().readAllBytes(), StandardCharsets.UTF_8))
                .startsWith("gradeId,gradeName,");
    }

    @Test
    void oneRedemptionRowIsWrittenPerSpentToken_andItIsNotTheTokenItself() throws Exception {
        // Every test in this class shares one schema and spends tokens; start
        // from empty so "one row" counts this token's row and nothing else.
        jdbc.update("delete from export_download_redemption", new MapSqlParameterSource());

        String token = issueTokenForAFinishedExport();
        exportService.download(token).content().close();

        List<String> hashes = jdbc.queryForList(
                "select token_hash from export_download_redemption", new MapSqlParameterSource(), String.class);
        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0)).hasSize(64).doesNotContain(token);

        // A refused replay must not append a second row — the refusal is the
        // insert failing, so a growing table would mean it is not the insert
        // doing the refusing.
        assertThatThrownBy(() -> exportService.download(token)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from export_download_redemption",
                new MapSqlParameterSource(), Integer.class)).isEqualTo(1);
    }

    /**
     * Two simultaneous presentations of one token: exactly one wins. This is
     * the case a select-then-insert would get wrong and the primary key gets
     * right, and it is not hypothetical — a double-clicked download link sends
     * two requests a few milliseconds apart.
     */
    @Test
    void twoConcurrentFetchesOfOneLinkYieldExactlyOneSuccess() throws Exception {
        String token = issueTokenForAFinishedExport();

        Callable<Boolean> attempt = () -> {
            try {
                exportService.download(token).content().close();
                return true;
            } catch (ApiException e) {
                assertThat(e.getMessage()).contains("already been used");
                return false;
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = pool.invokeAll(List.of(attempt, attempt));
            long successes = results.stream().filter(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new AssertionError("A concurrent download failed for an unexpected reason", e);
                }
            }).count();
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private String issueTokenForAFinishedExport() {
        return tokenOf(exportService.issueDownloadUrl(finishedExport().id(), adminAuth()));
    }

    /** The smallest real export there is — a bounded aggregate report, so the job finishes in one write. */
    private JobDto finishedExport() {
        JobDto job = exportService.create(new CreateExportRequest("participation"), "usr_test_admin");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
        while (Instant.now().isBefore(deadline)) {
            String status = jobRepository.findById(job.id()).orElseThrow().getStatus();
            if (status.equals("SUCCEEDED")) {
                return job;
            }
            if (status.equals("FAILED")) {
                throw new AssertionError("Export job failed: "
                        + jobRepository.findById(job.id()).orElseThrow().getErrorMessage());
            }
            sleep();
        }
        throw new AssertionError("Export job " + job.id() + " did not finish within timeout");
    }

    private String tokenOf(String downloadUrl) {
        return downloadUrl.substring(downloadUrl.indexOf("token=") + "token=".length());
    }

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Authentication adminAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
