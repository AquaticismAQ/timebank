package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.infrastructure.LogPartitionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B6.1: keeps `audit_log`/`login_log`'s monthly `RANGE` partitions rolling
 * — new partitions carved out of `p_future` ahead of need, partitions past
 * the 1-year retention (Design.md §16.3) dropped outright. Runs monthly;
 * cheap enough that daily would also be fine, but the partition boundary
 * only actually needs to move once a month.
 */
@Component
class LogPartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(LogPartitionMaintenanceJob.class);
    private static final List<String> PARTITIONED_TABLES = List.of("audit_log", "login_log");
    private static final int MONTHS_AHEAD = 3;
    private static final int RETENTION_MONTHS = 12;
    private static final Pattern MONTH_PARTITION = Pattern.compile("p_(\\d{4})_(\\d{2})");

    private final LogPartitionRepository repository;

    LogPartitionMaintenanceJob(LogPartitionRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 30 2 1 * *")
    @SchedulerLock(name = "logPartitionMaintenance", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    void run() {
        YearMonth currentMonth = YearMonth.now();
        for (String table : PARTITIONED_TABLES) {
            List<String> existing = repository.namedPartitions(table);
            for (int i = 0; i <= MONTHS_AHEAD; i++) {
                repository.ensurePartitionExists(table, currentMonth.plusMonths(i), existing);
            }

            YearMonth cutoff = currentMonth.minusMonths(RETENTION_MONTHS);
            for (String partitionName : repository.namedPartitions(table)) {
                YearMonth partitionMonth = parseMonth(partitionName);
                if (partitionMonth != null && partitionMonth.isBefore(cutoff)) {
                    log.warn("Dropping expired log partition {}.{} (retention {} months)", table, partitionName, RETENTION_MONTHS);
                    repository.dropPartition(table, partitionName);
                }
            }
        }
        log.info("Log partition maintenance complete for {}", PARTITIONED_TABLES);
    }

    private YearMonth parseMonth(String partitionName) {
        Matcher m = MONTH_PARTITION.matcher(partitionName);
        return m.matches() ? YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))) : null;
    }
}
