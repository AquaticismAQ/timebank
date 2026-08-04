package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.infrastructure.ShortLivedTableRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * B6.2: "every table has a lifecycle" (Design.md principle 9) —
 * `idempotency_record` "expires in days" (§16.3). Spring Session JDBC ships
 * its own periodic cleanup for the `session` table out of the box (visible
 * in the app's own logs as {@code cleanUpExpiredSessions}) — not
 * ShedLock-guarded, but a `DELETE WHERE expiry < now` is naturally
 * idempotent, so a second instance running it redundantly is wasted work,
 * not a correctness risk, unlike the jobs in this module that mutate
 * business data. Only `idempotency_record` needed a sweep built from
 * scratch here.
 */
@Component
class ShortLivedTableSweepJob {

    private static final Logger log = LoggerFactory.getLogger(ShortLivedTableSweepJob.class);
    private static final int IDEMPOTENCY_RETENTION_DAYS = 7;

    private final ShortLivedTableRepository repository;

    ShortLivedTableSweepJob(ShortLivedTableRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 15 3 * * *")
    @SchedulerLock(name = "idempotencyRecordSweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    void run() {
        Instant cutoff = Instant.now().minus(IDEMPOTENCY_RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = repository.deleteIdempotencyRecordsOlderThan(cutoff);
        log.info("Idempotency record sweep deleted {} rows older than {} days", deleted, IDEMPOTENCY_RETENTION_DAYS);
    }
}
