package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.domain.Job;
import cn.timebank.dataexchange.infrastructure.JobRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * D5's "ShedLock-guarded reclaim sweep... what makes the two-instance
 * topology safe": a job whose lease expired while still claimed as
 * running was interrupted (crash, deploy) — this resubmits it. Export and
 * import-validate restart from scratch (both side-effect-free; identical
 * output either way). Import-commit resumes from its checkpoint, since it
 * is the one run with a real side effect (crediting the ledger) that a
 * from-scratch restart would double.
 */
@Component
class JobReclaimSweep {

    private static final Logger log = LoggerFactory.getLogger(JobReclaimSweep.class);
    private static final List<String> RECLAIMABLE_STATUSES = List.of("RUNNING", "VALIDATING", "COMMITTING");

    private final JobRepository jobRepository;
    private final ExportJobRunner exportJobRunner;
    private final ImportJobRunner importJobRunner;
    private final JobProgressWriter progressWriter;

    JobReclaimSweep(JobRepository jobRepository, ExportJobRunner exportJobRunner,
                     ImportJobRunner importJobRunner, JobProgressWriter progressWriter) {
        this.jobRepository = jobRepository;
        this.exportJobRunner = exportJobRunner;
        this.importJobRunner = importJobRunner;
        this.progressWriter = progressWriter;
    }

    @Scheduled(fixedDelay = 120_000)
    @SchedulerLock(name = "jobReclaimSweep", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    void sweep() {
        List<Job> expired = jobRepository.findByStatusInAndLeaseExpiresAtBefore(RECLAIMABLE_STATUSES, Instant.now());
        for (Job job : expired) {
            log.warn("Reclaiming job {} ({}, {}) — lease expired while claimed by {}",
                    job.getId(), job.getKind(), job.getStatus(), job.getClaimedBy());
            switch (job.getStatus()) {
                case "RUNNING" -> exportJobRunner.runAsync(job.getId());
                case "VALIDATING" -> importJobRunner.validateAsync(job.getId());
                // Not resubmitted (issue 146). A commit is one transaction now, so
                // an interrupted one applied nothing — there is no progress to
                // resume, and an automatic re-run is the only remaining way to get
                // two live commits for one file. Marked failed instead, which is
                // what actually happened, and an operator retries it deliberately:
                // retrying re-runs the whole file, which is safe by construction.
                case "COMMITTING" -> progressWriter.fail(job.getId(), "COMMIT_FAILED",
                        "The commit was interrupted and nothing was imported. Start it again.");
                default -> log.error("Job {} in unreclaimable status {}", job.getId(), job.getStatus());
            }
        }
    }
}
