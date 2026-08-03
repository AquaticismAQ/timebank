package cn.timebank.caseflow.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountRequests.AdjustmentRequest;
import cn.timebank.caseflow.application.dto.CaseDto;
import cn.timebank.caseflow.application.dto.CaseRequests.DecideRequest;
import cn.timebank.caseflow.domain.Case;
import cn.timebank.caseflow.domain.CaseDecision;
import cn.timebank.caseflow.domain.CaseDecisionEvent;
import cn.timebank.caseflow.domain.CaseStateMachine;
import cn.timebank.caseflow.domain.CaseStateMachine.ActorRole;
import cn.timebank.caseflow.domain.CaseStatus;
import cn.timebank.caseflow.domain.CaseType;
import cn.timebank.caseflow.infrastructure.CaseDecisionEventRepository;
import cn.timebank.caseflow.infrastructure.CaseRepository;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.UserService;
import cn.timebank.notification.application.NotificationService;
import cn.timebank.student.application.StudentService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * B5.2's atomic resolution transaction — a separate bean for the same
 * reason {@code ReviewDecisionTransaction}/{@code ProposalApprovalTransaction}
 * are: {@code @Transactional} only applies through the Spring proxy, and
 * {@code CaseServiceImpl.decide} calls this via {@code IdempotencyService
 * .execute}'s supplier, which would otherwise be a self-invocation.
 */
@Component
class CaseDecisionTransaction {

    private final CaseRepository repository;
    private final CaseDecisionEventRepository decisionEventRepository;
    private final AccountService accountService;
    private final NotificationService notificationService;
    private final UserService userService;
    private final StudentService studentService;

    CaseDecisionTransaction(CaseRepository repository, CaseDecisionEventRepository decisionEventRepository,
                             AccountService accountService, NotificationService notificationService,
                             UserService userService, StudentService studentService) {
        this.repository = repository;
        this.decisionEventRepository = decisionEventRepository;
        this.accountService = accountService;
        this.notificationService = notificationService;
        this.userService = userService;
        this.studentService = studentService;
    }

    @Transactional
    CaseDto execute(String caseId, DecideRequest request, String actorId, String resolvedLanguage) {
        Case c = repository.findById(caseId).orElseThrow(() -> ApiException.notFound("Case not found."));
        ConcurrencyControl.checkVersion(c.getVersion(), request.caseVersion());

        CaseDecision decision = parseDecision(request.decision());
        CaseStatus target = switch (decision) {
            case RESOLVE -> CaseStatus.RESOLVED;
            case REQUEST_INFORMATION -> CaseStatus.NEEDS_INFORMATION;
            case REJECT -> CaseStatus.REJECTED;
        };

        // Effect: the implicit SUBMITTED -> IN_REVIEW step (first decision), then the real target.
        if (c.getStatus() == CaseStatus.SUBMITTED) {
            CaseStateMachine.transition(c, CaseStatus.IN_REVIEW, ActorRole.REVIEWER);
        }
        if (c.getStatus() != target) {
            CaseStateMachine.transition(c, target, ActorRole.REVIEWER);
        }
        c.setUpdatedAt(Instant.now());

        // Effect: the append-only decision event.
        CaseDecisionEvent event = new CaseDecisionEvent(OpaqueIds.generate("csdc"), caseId, actorId, decision,
                request.reasonCode(), request.comment(), request.caseVersion(), Instant.now());
        decisionEventRepository.save(event);

        // Effect (manualCorrection + resolve only): the ledger adjustment
        // (B5.2 — "the one case type that touches the ledger"; appeal and
        // supplement resolutions are communication + tracking only, per
        // Design.md Q6).
        if (decision == CaseDecision.RESOLVE && c.getType() == CaseType.MANUAL_CORRECTION) {
            if (c.getRequestedAmountCentiCoins() == null) {
                throw ApiException.invalidRequest(
                        "A manual correction must specify requestedAmountCentiCoins before it can be resolved.");
            }
            accountService.adjustForStudent(c.getStudentId(), c.getGradeId(), c.getClassId(),
                    new AdjustmentRequest(c.getRequestedAmountCentiCoins(), c.getReasonCode(), request.comment()), actorId);
        }

        // Effect: the notification, to the case's student.
        notifyStudent(c, decision, resolvedLanguage);

        repository.saveAndFlush(c);
        return toDto(c, resolvedLanguage);
    }

    private void notifyStudent(Case c, CaseDecision decision, String resolvedLanguage) {
        String templateCode = switch (decision) {
            case RESOLVE -> "case.resolved";
            case REQUEST_INFORMATION -> "case.needsInformation";
            case REJECT -> "case.rejected";
        };
        userService.findUserIdByStudentId(c.getStudentId())
                .ifPresent(recipientUserId -> notificationService.notify(recipientUserId, templateCode,
                        "case", c.getId(), resolvedLanguage));
    }

    private CaseDecision parseDecision(String raw) {
        try {
            return CaseDecision.valueOf(raw.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Unknown decision: " + raw);
        }
    }

    private CaseDto toDto(Case c, String resolvedLanguage) {
        // Re-fetching via a lightweight inline mapper avoids a circular
        // dependency on CaseService (which depends on this bean).
        String studentName = studentService.getDetail(c.getStudentId(), false, "system", resolvedLanguage).displayName();
        return new CaseDto(c.getId(), c.getType().name(), c.getStudentId(), studentName, c.getSubjectType(),
                c.getSubjectId(), c.getTitle(), c.getDescription(), c.getRequestedAmountCentiCoins(), c.getReasonCode(),
                c.getStatus().name(), c.getAssignedReviewerId(), c.getVersion(), c.getCreatedAt(), c.getSubmittedAt(),
                c.getUpdatedAt());
    }
}
