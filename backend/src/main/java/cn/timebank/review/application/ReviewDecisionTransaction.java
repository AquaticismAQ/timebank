package cn.timebank.review.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountTransactionDto;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.UserService;
import cn.timebank.notification.application.NotificationService;
import cn.timebank.progress.application.ProgressService;
import cn.timebank.review.application.dto.ReviewRequests.DecideRequest;
import cn.timebank.review.domain.Decision;
import cn.timebank.review.domain.ReviewDecisionEvent;
import cn.timebank.review.infrastructure.ReviewDecisionRepository;
import cn.timebank.rules.application.RuleResolutionService;
import cn.timebank.rules.application.dto.EffectiveRule;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.RecordReviewSnapshot;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The actual five-effect approval transaction (B3.12), split into its own
 * bean for the same reason {@code IdempotencyTransactions} is: {@code
 * @Transactional} only takes effect through the Spring proxy, and {@code
 * ReviewServiceImpl.decide} calls this via {@code IdempotencyService
 * .execute}'s supplier — a self-invocation would silently run with no
 * transaction at all (the same self-invocation gotcha B0/B1 documented
 * for idempotency and audit).
 */
@Component
class ReviewDecisionTransaction {

    private final ServiceRecordService serviceRecordService;
    private final ReviewDecisionRepository reviewDecisionRepository;
    private final AccountService accountService;
    private final ProgressService progressService;
    private final NotificationService notificationService;
    private final RuleResolutionService ruleResolutionService;
    private final UserService userService;

    ReviewDecisionTransaction(ServiceRecordService serviceRecordService, ReviewDecisionRepository reviewDecisionRepository,
                              AccountService accountService, ProgressService progressService,
                              NotificationService notificationService, RuleResolutionService ruleResolutionService,
                              UserService userService) {
        this.serviceRecordService = serviceRecordService;
        this.reviewDecisionRepository = reviewDecisionRepository;
        this.accountService = accountService;
        this.progressService = progressService;
        this.notificationService = notificationService;
        this.ruleResolutionService = ruleResolutionService;
        this.userService = userService;
    }

    @Transactional
    ServiceRecordDto execute(String recordId, DecideRequest request, String actorId, Authentication auth, String resolvedLanguage) {
        Decision decision = parseDecision(request.decision());
        String targetStatus = switch (decision) {
            case APPROVE -> "APPROVED";
            case RETURN_FOR_CHANGES -> "RETURNED";
            case REJECT -> "REJECTED";
            case ESCALATE -> "UNDER_REVIEW";
        };

        // Effect 1 (partial) + effect 2: the record's status transition (and
        // the implicit SUBMITTED -> UNDER_REVIEW step if this is the first
        // decision) happens inside servicerecord's own application layer —
        // review may not touch servicerecord.domain/infrastructure directly.
        RecordReviewSnapshot snapshot = serviceRecordService.beginReviewTransition(
                recordId, request.recordVersion(), targetStatus, actorId);

        // Effect 1: the append-only review decision event.
        ReviewDecisionEvent event = new ReviewDecisionEvent(OpaqueIds.generate("rev"), recordId, actorId, decision,
                request.reasonCode(), request.comment(), request.recordVersion(), Instant.now());
        reviewDecisionRepository.save(event);

        String recipientUserId = userService.findUserIdByStudentId(snapshot.studentId()).orElse(null);

        if (decision == Decision.APPROVE) {
            Instant serviceInstant = snapshot.serviceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            EffectiveRule rule = ruleResolutionService.resolveEffectiveAt(serviceInstant)
                    .orElseThrow(() -> new IllegalStateException(
                            "No rule is in effect for service date " + snapshot.serviceDate()));
            int roundedMinutes = rule.roundToNearestGranularity(snapshot.durationMinutes());
            int creditedCentiCoins = rule.convertMinutesToCentiCoins(roundedMinutes);

            // Effect 3: the ledger transaction (row-locked, B3.9).
            AccountTransactionDto transaction = accountService.credit(snapshot.studentId(), snapshot.gradeId(),
                    snapshot.classId(), creditedCentiCoins, "serviceRecord", recordId, "SERVICE_APPROVED", actorId);
            serviceRecordService.applyCredit(recordId, creditedCentiCoins, transaction.id());

            // Effect 4: the progress projection.
            progressService.addCredit(snapshot.studentId(), roundedMinutes, creditedCentiCoins);

            // Effect 5: the notification.
            if (recipientUserId != null) {
                notificationService.notify(recipientUserId, "serviceRecord.approved", "serviceRecord", recordId, resolvedLanguage);
            }
        } else if (decision == Decision.RETURN_FOR_CHANGES && recipientUserId != null) {
            notificationService.notify(recipientUserId, "serviceRecord.returned", "serviceRecord", recordId, resolvedLanguage);
        } else if (decision == Decision.REJECT && recipientUserId != null) {
            notificationService.notify(recipientUserId, "serviceRecord.rejected", "serviceRecord", recordId, resolvedLanguage);
        }
        // ESCALATE: an internal reviewer action — no student-facing notification.

        return serviceRecordService.getDetail(recordId, auth, resolvedLanguage);
    }

    private Decision parseDecision(String raw) {
        try {
            return Decision.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Unknown decision: " + raw);
        }
    }
}
