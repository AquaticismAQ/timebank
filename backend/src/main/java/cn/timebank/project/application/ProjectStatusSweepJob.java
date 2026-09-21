package cn.timebank.project.application;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances project statuses that the clock has overtaken (issue 145).
 *
 * <h2>Why this is needed when every read already syncs</h2>
 *
 * <p>{@code ProjectServiceImpl.syncTimeBasedStatus} runs on every path a user
 * can reach — list, detail, publish, archive, cancel, update, and the locking
 * read registration goes through — so a stale status is never <em>shown</em>
 * and never gates a sign-up. B4.2 took that as sufficient.</p>
 *
 * <p>It is not quite, because two readers bypass the service. The dashboard and
 * reports count {@code status in ('PUBLISHED', 'REGISTRATION_OPEN',
 * 'IN_PROGRESS')} straight out of the table, so a project that should have
 * archived itself at its registration close is counted as active for as long as
 * nobody happens to open it. The figure is wrong without anything looking
 * wrong, which is the worst shape for a number on a dashboard.</p>
 *
 * <h2>Five minutes, not one</h2>
 *
 * <p>Nothing a person sees depends on this — the reads already correct
 * themselves — so the only thing the interval buys is how stale an aggregate
 * can be, and five minutes bounds that without making this the only
 * minutely job in the codebase.</p>
 *
 * <p>{@code @SchedulerLock} for the same reason every other job in here has it:
 * production is one systemd unit today, and the lock is what keeps that an
 * operational fact rather than an assumption baked into the code. The work is
 * idempotent regardless — "advance to what the clock says" gives the same
 * answer however many times it runs.</p>
 */
@Component
class ProjectStatusSweepJob {

    private static final Logger log = LoggerFactory.getLogger(ProjectStatusSweepJob.class);

    private final ProjectService projectService;

    ProjectStatusSweepJob(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "projectStatusSweep", lockAtMostFor = "PT4M", lockAtLeastFor = "PT10S")
    void run() {
        int advanced = projectService.reconcileTimeBasedStatuses();
        // Only when something moved: a line every five minutes saying "nothing
        // happened" is how a log stops being read.
        if (advanced > 0) {
            log.info("Project status sweep advanced {} project(s) past a time boundary", advanced);
        }
    }
}
