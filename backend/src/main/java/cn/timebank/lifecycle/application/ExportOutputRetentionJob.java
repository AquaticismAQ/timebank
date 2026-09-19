package cn.timebank.lifecycle.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.file.application.FileStorage;
import cn.timebank.lifecycle.infrastructure.ExportOutputRetentionRepository;
import cn.timebank.lifecycle.infrastructure.ExportOutputRetentionRepository.ExpiredExportOutput;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Deletes produced export files once they are old enough, and forgets the
 * storage key on the job row that produced them.
 *
 * <p>F-11 (`Docs/optimization/11-DataImportExportOptimization.md` §2.11): until
 * this existed, every export ever run was still on disk, which made
 * `12-DataExport.md` §8's "no long-lived server-side copies" the one line in
 * that section describing an intention rather than the system. It matters more
 * than a housekeeping job usually would, because §6.1 removes System Admin's
 * export permission specifically to close a student-data exit — and a
 * permission is not the only route to a file sitting on the server they
 * administer.</p>
 *
 * <p>Same shape as {@link ShortLivedTableSweepJob}: age-based, ShedLock-guarded
 * so only one instance of a two-instance topology acts, and loud enough in the
 * log that a run can be reconstructed afterwards. Unlike that one it also
 * touches the filesystem, so the two halves of each deletion are ordered
 * deliberately — see {@link #sweep}.</p>
 */
@Component
class ExportOutputRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ExportOutputRetentionJob.class);

    /**
     * How long a produced export stays downloadable.
     *
     * <p>Seven days, and the number is a trade between two honest needs rather
     * than a round guess. Downwards: the file is a CSV of student data whose
     * whole point was to leave the system, so the shorter the window, the less
     * of it accumulates on a box and in whatever backs that box up. Upwards: an
     * export is asynchronous, and §2.11 deliberately rejected deleting the file
     * on first download, so re-issuing a link has to keep working for as long
     * as someone might plausibly come back for the file they asked for — an
     * export started late on a Friday, collected on Monday, is the ordinary
     * case, and a public holiday in between is not rare. A week covers that
     * with slack while still bounding the disk to a week of exports rather than
     * a term of them.</p>
     *
     * <p>It is also the same number {@link ShortLivedTableSweepJob} uses for
     * `idempotency_record`, which is worth something on its own: an operator
     * reasoning about what this server still holds has one retention figure to
     * remember for short-lived data, not two.</p>
     */
    static final int OUTPUT_RETENTION_DAYS = 7;

    /**
     * Spent download tokens are kept a day. The row's only job is to refuse a
     * replay of a token that is still inside its 300-second TTL; past that the
     * expiry check refuses it anyway, so the row is dead weight. A day rather
     * than an hour because it costs nothing and leaves a same-day trace of
     * which job was fetched for anyone reading the table after an incident.
     */
    static final int REDEMPTION_RETENTION_DAYS = 1;

    /**
     * Files deleted per run. The first run after this job ships has to work
     * through every export the system ever produced, and a bounded batch keeps
     * that from being one unbounded burst of filesystem work; the remainder is
     * picked up by the next run, oldest first.
     */
    private static final int MAX_OUTPUTS_PER_RUN = 1000;

    private final ExportOutputRetentionRepository repository;
    private final FileStorage fileStorage;
    private final AuditService auditService;

    ExportOutputRetentionJob(ExportOutputRetentionRepository repository, FileStorage fileStorage,
                              AuditService auditService) {
        this.repository = repository;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @SchedulerLock(name = "exportOutputRetentionSweep", lockAtMostFor = "PT15M", lockAtLeastFor = "PT10S")
    void runDaily() {
        sweep(Instant.now());
    }

    /**
     * The run itself, with "now" passed in so a test can cross the retention
     * boundary for real instead of waiting a week for it.
     *
     * <p>Per file: delete the bytes first, then clear `result_ref`. That order
     * is the self-healing one. If the delete succeeds and the update then
     * fails, the row is found again by the next run and both steps retried
     * (deleting an already-absent key is a no-op), so the file does not survive
     * as an orphan nothing will ever look for again — which is precisely the
     * failure this job exists to prevent. The other order trades a permanent
     * orphan for a temporary broken link, and the orphan is the worse of the
     * two.</p>
     */
    SweepResult sweep(Instant now) {
        Instant outputCutoff = now.minus(OUTPUT_RETENTION_DAYS, ChronoUnit.DAYS);
        List<ExpiredExportOutput> expired = repository.findExportOutputsOlderThan(outputCutoff, MAX_OUTPUTS_PER_RUN);

        int filesDeleted = 0;
        int failures = 0;
        for (ExpiredExportOutput output : expired) {
            try {
                fileStorage.delete(output.storageKey());
                repository.clearResultRef(output.jobId(), output.storageKey());
                filesDeleted++;
            } catch (RuntimeException e) {
                // One unreadable file must not strand the rest of the batch;
                // the row keeps its result_ref, so the next run retries it.
                failures++;
                log.error("Export output retention: could not remove {} for job {} — will retry next run",
                        output.storageKey(), output.jobId(), e);
            }
        }

        int redemptionsDeleted = repository.deleteDownloadRedemptionsOlderThan(
                now.minus(REDEMPTION_RETENTION_DAYS, ChronoUnit.DAYS));

        log.info("Export output retention: deleted {} export files older than {} days ({} failed), "
                        + "and {} spent download tokens older than {} days",
                filesDeleted, OUTPUT_RETENTION_DAYS, failures, redemptionsDeleted, REDEMPTION_RETENTION_DAYS);

        // Audited only when something was actually deleted. A daily "nothing to
        // do" row would bury the interesting ones in a log that is partitioned
        // and kept for years; the INFO line above is the heartbeat, the audit
        // row is the record of data leaving the server. Deleting a file someone
        // may come back for is exactly the kind of unattended act §13's audit
        // log exists to make answerable after the fact.
        if (filesDeleted > 0) {
            auditService.record(new AuditEntry("scheduler", null, "lifecycle.exportOutputRetention",
                    "job", null, null,
                    Map.of("filesDeleted", filesDeleted, "failures", failures,
                            "retentionDays", OUTPUT_RETENTION_DAYS), null));
        }

        return new SweepResult(filesDeleted, failures, redemptionsDeleted);
    }

    /** What one run did — returned for tests and for the log line above, not exposed anywhere else. */
    record SweepResult(int filesDeleted, int failures, int redemptionsDeleted) {
    }
}
