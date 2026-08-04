package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.infrastructure.PurgeRunRepository;
import cn.timebank.lifecycle.infrastructure.ReconciliationRunRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * B6.4/B6.7: "an alert if it fails to run" — a job that silently stops
 * running is exactly the PIPL compliance failure mode Design.md §16.3
 * names for the purge, and R4 names for reconciliation. Windows are
 * generous relative to each job's own cadence (daily/nightly/weekly) so a
 * single slow run or a brief maintenance window doesn't false-alarm.
 */
@Component
class LifecycleWatchdogJob {

    private static final Logger log = LoggerFactory.getLogger(LifecycleWatchdogJob.class);

    private final PurgeRunRepository purgeRunRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;

    LifecycleWatchdogJob(PurgeRunRepository purgeRunRepository, ReconciliationRunRepository reconciliationRunRepository) {
        this.purgeRunRepository = purgeRunRepository;
        this.reconciliationRunRepository = reconciliationRunRepository;
    }

    @Scheduled(cron = "0 0 6 * * *")
    @SchedulerLock(name = "lifecycleWatchdog", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    void run() {
        if (!purgeRunRepository.hasSucceededSince(Instant.now().minus(36, ChronoUnit.HOURS))) {
            log.error("LIFECYCLE ALERT: the cohort purge dry-run job has not completed successfully in over 36 hours — check CohortPurgeJob's schedule and logs");
        }
        if (!reconciliationRunRepository.hasSucceededSince(Instant.now().minus(36, ChronoUnit.HOURS))) {
            log.error("LIFECYCLE ALERT: ledger reconciliation has not completed successfully in over 36 hours — check LedgerReconciliationJob's schedule and logs");
        }
    }
}
