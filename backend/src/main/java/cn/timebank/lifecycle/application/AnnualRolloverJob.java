package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.application.dto.AnnualRolloverReportDto;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Design/*&#47;13-AnnualRollover.md §3 — the rollover runs itself when the new
 * academic year begins.
 *
 * <p><b>Why daily rather than a cron pinned to 1 September.</b> A once-a-year
 * trigger has exactly one chance to fire, and if the server happens to be down
 * that night — a deploy, a restart, a failed host — nothing promotes anyone and
 * nothing says so. The next signal would be a teacher noticing their class list
 * is a year stale.</p>
 *
 * <p>So the job wakes daily and asks whether the current academic year has
 * already been rolled over. On 1 September it has not, and it runs; on 2
 * September it has, and it records a SKIPPED row and stops. The observable
 * behaviour is "it happens on 1 September", with the difference that a missed
 * night is recovered the following morning instead of silently lost.</p>
 *
 * <p>Runs at 02:00, after {@code CohortPurgeJob}'s own 02:00 slot in the same
 * way — both are locked by ShedLock, so on a multi-instance deployment exactly
 * one of them performs the work.</p>
 */
@Component
class AnnualRolloverJob {

    private static final Logger log = LoggerFactory.getLogger(AnnualRolloverJob.class);

    private final AnnualRolloverService annualRolloverService;

    AnnualRolloverJob(AnnualRolloverService annualRolloverService) {
        this.annualRolloverService = annualRolloverService;
    }

    @Scheduled(cron = "0 30 2 * * *")
    @SchedulerLock(name = "annualRollover", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    void runDaily() {
        AnnualRolloverReportDto report = annualRolloverService.runIfDue("scheduler");
        if ("SKIPPED".equals(report.outcome())) {
            // The ordinary case on 364 days of the year. Logged at debug so the
            // one day it actually does something is not buried.
            log.debug("Annual rollover: {} already rolled over", report.academicYearId());
            return;
        }
        log.info("Annual rollover into {} complete: {} promoted, {} graduated, {} skipped, {} inconsistent{}",
                report.academicYearId(), report.studentsPromoted(), report.studentsGraduated(),
                report.studentsSkipped(), report.inconsistentGrades(),
                report.message() == null ? "" : " — " + report.message());
        if (report.inconsistentGrades() > 0) {
            // §5: the only signal that a previous year's run was missed.
            log.warn("Annual rollover: {} active students have a grade that does not match their enrolment cohort",
                    report.inconsistentGrades());
        }
    }
}
