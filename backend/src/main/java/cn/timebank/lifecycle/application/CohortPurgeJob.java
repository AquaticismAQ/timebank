package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.application.dto.PurgeRunSummaryDto;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * B6.3/B6.4. The automated schedule is deliberately **dry-run only** —
 * "the first production run is executed deliberately rather than on a
 * timer" (Design.md §16.3 point 5) means no code path may auto-delete a
 * student's data without an explicit, authenticated admin action. This
 * job's job is to keep the compliance record current (so the
 * non-execution alert has something to check) and to surface, in logs,
 * exactly what a real run would do — never to perform one. The real run
 * is {@code POST /admin/purge-runs?dryRun=false}, triggered by a human.
 */
@Component
class CohortPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(CohortPurgeJob.class);

    private final CohortPurgeService cohortPurgeService;

    CohortPurgeJob(CohortPurgeService cohortPurgeService) {
        this.cohortPurgeService = cohortPurgeService;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "cohortPurgeDryRun", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    void runDaily() {
        // cohortPurgeService.run() always records at least one purge_run row,
        // even a "0 students scanned" heartbeat — that's what lets the
        // non-execution watchdog (LifecycleWatchdogJob) tell "ran, nothing to
        // do" apart from "never ran". Only non-empty cohorts warrant a WARN.
        List<PurgeRunSummaryDto> summaries = cohortPurgeService.run(true, "scheduler");
        List<PurgeRunSummaryDto> withEligibleStudents = summaries.stream()
                .filter(s -> s.studentsScanned() > 0).toList();
        if (withEligibleStudents.isEmpty()) {
            log.info("Cohort purge dry run: no graduation-year cohort is past its retention window today");
        } else {
            for (PurgeRunSummaryDto summary : withEligibleStudents) {
                log.warn("Cohort purge dry run found {} students eligible for real deletion (policy {}) — " +
                                "no action taken automatically; requires POST /admin/purge-runs?dryRun=false",
                        summary.studentsScanned(), summary.policyVersion());
            }
        }
    }
}
