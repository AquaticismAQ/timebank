package cn.timebank.proposal.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.UserService;
import cn.timebank.notification.application.NotificationService;
import cn.timebank.proposal.application.dto.ProposalDto;
import cn.timebank.proposal.application.dto.ProposalRequests.DecideRequest;
import cn.timebank.proposal.domain.EventProposal;
import cn.timebank.proposal.domain.ProposalDecision;
import cn.timebank.proposal.domain.ProposalReviewEvent;
import cn.timebank.proposal.domain.ProposalStateMachine;
import cn.timebank.proposal.domain.ProposalStateMachine.ActorRole;
import cn.timebank.proposal.domain.ProposalStatus;
import cn.timebank.proposal.infrastructure.EventProposalRepository;
import cn.timebank.proposal.infrastructure.ProposalReviewEventRepository;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectGenerationRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * B4.11's atomic project-generation transaction — a separate bean for the
 * same reason {@code ReviewDecisionTransaction} (B3.12) is: {@code
 * @Transactional} only applies through the Spring proxy, and {@code
 * ProposalServiceImpl.decide} calls this via {@code IdempotencyService
 * .execute}'s supplier, which would otherwise be a self-invocation. Kept as
 * a dedicated bean specifically so forced-failure tests can swap one
 * collaborator field at a time (matching {@code ApprovalTransactionIT}'s
 * approach in B3).
 */
@Component
class ProposalApprovalTransaction {

    private final EventProposalRepository repository;
    private final ProposalReviewEventRepository reviewEventRepository;
    private final ProjectService projectService;
    private final NotificationService notificationService;
    private final UserService userService;

    ProposalApprovalTransaction(EventProposalRepository repository, ProposalReviewEventRepository reviewEventRepository,
                                ProjectService projectService, NotificationService notificationService,
                                UserService userService) {
        this.repository = repository;
        this.reviewEventRepository = reviewEventRepository;
        this.projectService = projectService;
        this.notificationService = notificationService;
        this.userService = userService;
    }

    @Transactional
    ProposalDto execute(String proposalId, DecideRequest request, String actorId, String resolvedLanguage) {
        EventProposal proposal = repository.findById(proposalId)
                .orElseThrow(() -> ApiException.notFound("Event proposal not found."));
        ConcurrencyControl.checkVersion(proposal.getVersion(), request.proposalVersion());

        ProposalDecision decision = parseDecision(request.decision());
        ProposalStatus target = switch (decision) {
            case APPROVE -> ProposalStatus.APPROVED;
            case REQUEST_CHANGES -> ProposalStatus.CHANGES_REQUESTED;
            case REJECT -> ProposalStatus.REJECTED;
            case RECOMMEND_APPROVAL -> ProposalStatus.UNDER_REVIEW;
        };

        // Effect: the implicit SUBMITTED -> UNDER_REVIEW step (first decision), then the real target.
        if (proposal.getStatus() == ProposalStatus.SUBMITTED) {
            ProposalStateMachine.transition(proposal, ProposalStatus.UNDER_REVIEW, ActorRole.REVIEWER);
        }
        if (proposal.getStatus() != target) {
            ProposalStateMachine.transition(proposal, target, ActorRole.REVIEWER);
        }
        proposal.setUpdatedAt(Instant.now());

        // Effect: the append-only review event.
        ProposalReviewEvent event = new ProposalReviewEvent(OpaqueIds.generate("eprw"), proposalId, actorId,
                decision, request.reasonCode(), request.comment(), request.proposalVersion(), Instant.now());
        reviewEventRepository.save(event);

        if (decision == ProposalDecision.APPROVE) {
            // Effect: the generated official project draft.
            List<String> organizers = new ArrayList<>();
            organizers.add(proposal.getProposerStudentId());
            if (proposal.getCoOrganizerStudentIds() != null) {
                organizers.addAll(proposal.getCoOrganizerStudentIds());
            }
            String projectId = projectService.createFromProposal(new ProjectGenerationRequest(
                    proposal.getTitle(), proposal.getObjective(), proposal.getDescription(), proposal.getCategoryId(),
                    proposal.getOrganizationId(), proposal.getProposedSupervisorId(), proposalId,
                    proposal.getPlannedStartAt(), proposal.getPlannedEndAt(), proposal.getLocation(),
                    proposal.getCapacity(), proposal.getProposedRecognizedMinutes(), proposal.getEvidencePlan(),
                    organizers));

            // Effect: backfill approvedProjectId — an approved proposal without a linked project cannot exist (invariant 6).
            proposal.setApprovedProjectId(projectId);
        }

        // Effect: the notification, to the proposer.
        notifyProposer(proposal, decision);

        repository.saveAndFlush(proposal);
        return toDto(proposal, resolvedLanguage);
    }

    private void notifyProposer(EventProposal proposal, ProposalDecision decision) {
        String templateCode = switch (decision) {
            case APPROVE -> "eventProposal.approved";
            case REQUEST_CHANGES -> "eventProposal.changesRequested";
            case REJECT -> "eventProposal.rejected";
            case RECOMMEND_APPROVAL -> null; // internal reviewer action, no student-facing notification
        };
        if (templateCode == null) {
            return;
        }
        userService.findUserIdByStudentId(proposal.getProposerStudentId())
                .ifPresent(recipientUserId -> notificationService.notify(recipientUserId, templateCode, "eventProposal", proposal.getId()));
    }

    private ProposalDecision parseDecision(String raw) {
        try {
            return ProposalDecision.valueOf(convertCamelToUpperSnake(raw));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Unknown decision: " + raw);
        }
    }

    private String convertCamelToUpperSnake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }

    private ProposalDto toDto(EventProposal p, String resolvedLanguage) {
        // Re-fetching via a lightweight inline mapper avoids a circular
        // dependency on ProposalService (which depends on this bean).
        return new ProposalDto(p.getId(), p.getProposerStudentId(), null, p.getCoOrganizerStudentIds(),
                p.getTitle(), p.getObjective(), p.getDescription(), p.getExpectedOutcome(),
                p.getCategoryId(), null, p.getPlannedStartAt(), p.getPlannedEndAt(), p.getLocation(), p.getFormat(),
                p.getTargetParticipants(), p.getCapacity(), p.getVolunteerTasks(),
                p.getProposedSupervisorId(), null, p.getOrganizationId(), null, p.getExternalContact(),
                p.getSafetyPlan(), p.getResourceNeeds(), p.getRecruitmentPlan(),
                p.getProposedRecognizedMinutes(), p.getEvidencePlan(),
                p.getStatus().name(), p.getCurrentRevision(), p.getAssignedReviewerId(), p.getApprovedProjectId(),
                p.getVersion(), p.getCreatedAt(), p.getSubmittedAt(), p.getUpdatedAt());
    }
}
