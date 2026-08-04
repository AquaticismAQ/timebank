package cn.timebank.lifecycle.application;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** B6.7: a nightly checkpointed pass, plus a weekly full re-sum as the safety net (Design.md §16.2/D12). Both delegate to {@link LedgerReconciliationService}, which does the actual alerting (structured ERROR logs, per-mismatch) and audit-trail writes. */
@Component
class LedgerReconciliationJob {

    private final LedgerReconciliationService reconciliationService;

    LedgerReconciliationJob(LedgerReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "ledgerReconciliationCheckpoint", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    void runNightly() {
        reconciliationService.runCheckpoint();
    }

    @Scheduled(cron = "0 0 4 * * SUN")
    @SchedulerLock(name = "ledgerReconciliationFull", lockAtMostFor = "PT1H", lockAtLeastFor = "PT10S")
    void runWeekly() {
        reconciliationService.runFull();
    }
}
