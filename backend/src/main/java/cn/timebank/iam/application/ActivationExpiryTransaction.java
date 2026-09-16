package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.iam.infrastructure.ActivationExpiryRepository;
import cn.timebank.iam.infrastructure.ActivationExpiryRepository.ActivationWindow;
import cn.timebank.iam.infrastructure.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The transactional half of the activation expiry sweep
 * (10-StudentIdentityAndOnboarding.md §3.6): moves accounts whose activation
 * window closed without anyone redeeming the code from
 * {@code PENDING_ACTIVATION} to {@code ACTIVATION_EXPIRED}.
 *
 * <p>Separate from {@link ActivationExpirySweep} for the reason every other
 * {@code *Transaction} component here is separate: the scheduling and
 * locking concerns belong to the job, the transaction boundary belongs to
 * the work, and a test can drive the work with an explicit clock without
 * going anywhere near the scheduler.</p>
 *
 * <p>One transaction per run, not per account: a failure (an optimistic
 * lock lost to a student activating at that instant) rolls back the whole
 * batch, the next run picks it up again, and the audit row can never claim
 * a transition that did not commit.</p>
 */
@Component
class ActivationExpiryTransaction {

    private final ActivationExpiryRepository activationExpiryRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    ActivationExpiryTransaction(ActivationExpiryRepository activationExpiryRepository,
                                 UserRepository userRepository, AuditService auditService) {
        this.activationExpiryRepository = activationExpiryRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    SweepResult expireClosedWindows(Instant now, int batchLimit) {
        List<String> candidateIds = activationExpiryRepository.findPendingUserIdsWithAnExpiredCode(now, batchLimit);
        if (candidateIds.isEmpty()) {
            return SweepResult.nothingToDo();
        }
        Map<String, List<ActivationWindow>> windowsByUser = activationExpiryRepository
                .findWindowsForUsers(candidateIds).stream()
                .collect(Collectors.groupingBy(ActivationWindow::userId));

        List<User> toExpire = new ArrayList<>();
        int alreadyActivated = 0;
        int windowStillOpen = 0;
        for (User user : userRepository.findAllById(candidateIds)) {
            // §2.7's principle, and §2.6's definition of "activated": the
            // stored password hash is the ground truth, not the code rows and
            // not the status. An account that has chosen a password is
            // untouchable here however stale the codes pointing at it look —
            // including the student who activated in the seconds between the
            // candidate query above and this read.
            if (user.hasUsableCredential() || user.getStatus() != UserStatus.PENDING_ACTIVATION) {
                alreadyActivated++;
                continue;
            }
            List<ActivationWindow> windows = windowsByUser.getOrDefault(user.getId(), List.of());
            // A live code means the student can still get in, so the window has
            // not closed — §2.7 regeneration leaves exactly this shape behind:
            // yesterday's expired code beside today's reissued one. No codes at
            // all means the slips were never generated, which is not an expiry.
            if (windows.isEmpty() || windows.stream().anyMatch(window -> window.isLive(now))) {
                windowStillOpen++;
                continue;
            }
            user.setStatus(UserStatus.ACTIVATION_EXPIRED);
            user.setUpdatedAt(now);
            toExpire.add(user);
        }

        if (toExpire.isEmpty()) {
            return new SweepResult(List.of(), alreadyActivated, windowStillOpen);
        }
        userRepository.saveAll(toExpire);
        List<String> expiredIds = toExpire.stream().map(User::getId).toList();
        // One row for the run, not one per account — see the class comment on
        // ActivationExpirySweep for why.
        auditService.record(new AuditEntry(null, null, "user.activationExpirySweep", "user", null,
                null, new ExpirySweepSummary(expiredIds.size(), expiredIds), null));
        return new SweepResult(expiredIds, alreadyActivated, windowStillOpen);
    }

    /**
     * What one run did. The two skip counts exist to be logged: "swept 0 of 40
     * candidates" is a symptom worth seeing, and which of the two reasons it
     * was tells an operator whether codes are being reissued faster than they
     * expire or whether the candidate query has drifted.
     */
    record SweepResult(List<String> expiredUserIds, int alreadyActivated, int windowStillOpen) {

        static SweepResult nothingToDo() {
            return new SweepResult(List.of(), 0, 0);
        }

        int expiredCount() {
            return expiredUserIds.size();
        }
    }

    /** The audit row's {@code after} summary: compact JSON, bounded by the sweep's batch limit. */
    record ExpirySweepSummary(int expiredCount, List<String> userIds) {
    }
}
