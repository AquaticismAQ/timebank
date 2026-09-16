package cn.timebank.iam.application;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 10-StudentIdentityAndOnboarding.md §3.6: "a scheduled job moves expired,
 * never-activated accounts to the expired state. A lazy check at login is
 * not enough: uncollected accounts would otherwise sit looking pending
 * forever in the admin view."
 *
 * <p>That sentence is the whole justification for a job rather than a check
 * on the login path. An account whose slip was never collected is, by
 * definition, one nobody is logging into — so the only place the truth can
 * surface is a list an admin reads, and only a sweep can put it there.</p>
 *
 * <p>{@code @Scheduled} + {@code @SchedulerLock} follows the pattern set by
 * the grade-summary materialisation (B5.8) and the import reclaim sweep
 * (B5.11): the lock makes the job safe under Design.md §17.1's two-instance
 * topology even though only one instance runs today. The transaction lives
 * in {@link ActivationExpiryTransaction}, outside the lock's method, so the
 * lock is held across the whole run and never inside it.</p>
 *
 * <h2>Why one audit row per run</h2>
 *
 * Every mutation is audited, and a status transition is a mutation — but
 * granularity is a choice, and a row per account is the wrong one here. A
 * sweep after a school-wide import could transition several hundred accounts
 * in one second, all for the same reason, none of them the act of any person.
 * That is precisely the shape of noise issue 085 removed an event for:
 * {@code student.viewSensitive} was half the audit log and told a reader
 * nothing per row. A run-level row keeps what an auditor actually asks —
 * when the sweep ran, how many accounts it moved, and which ones (the ids are
 * in the summary, bounded by the batch limit) — at one row instead of
 * hundreds. Written only when the run moved something: an hourly "expired 0
 * accounts" row would out-noise the event it records.
 */
@Component
class ActivationExpirySweep {

    private static final Logger log = LoggerFactory.getLogger(ActivationExpirySweep.class);

    private final ActivationExpiryTransaction expiryTransaction;
    private final int batchLimit;

    ActivationExpirySweep(ActivationExpiryTransaction expiryTransaction,
                           @Value("${timebank.activation.expiry-sweep.batch-limit:500}") int batchLimit) {
        this.expiryTransaction = expiryTransaction;
        this.batchLimit = batchLimit;
    }

    /**
     * Hourly by default. Not more often: nothing downstream reacts to the
     * transition, the window it closes is measured in days (§2.6), and the
     * only reader is an admin looking at a list. Not daily either: an admin
     * who reissues a batch the morning after it lapsed should see the truth
     * before the school day ends.
     *
     * <p>A backlog larger than {@code batchLimit} drains over successive runs
     * rather than in one long transaction — oldest expiry first, so it drains
     * in a fixed order.</p>
     */
    @Scheduled(cron = "${timebank.activation.expiry-sweep.cron:0 40 * * * *}")
    @SchedulerLock(name = "activationExpirySweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    void sweep() {
        ActivationExpiryTransaction.SweepResult result = expiryTransaction.expireClosedWindows(Instant.now(), batchLimit);
        if (result.expiredCount() > 0) {
            log.info("Activation expiry sweep moved {} account(s) to ACTIVATION_EXPIRED "
                            + "(skipped {} already activated, {} still inside an open window)",
                    result.expiredCount(), result.alreadyActivated(), result.windowStillOpen());
        } else {
            log.debug("Activation expiry sweep moved no accounts (skipped {} already activated, {} still open)",
                    result.alreadyActivated(), result.windowStillOpen());
        }
    }
}
