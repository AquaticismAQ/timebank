package cn.timebank.proposal.domain;

import cn.timebank.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static cn.timebank.proposal.domain.ProposalStateMachine.ActorRole.OWNER;
import static cn.timebank.proposal.domain.ProposalStateMachine.ActorRole.REVIEWER;
import static cn.timebank.proposal.domain.ProposalStatus.APPROVED;
import static cn.timebank.proposal.domain.ProposalStatus.CHANGES_REQUESTED;
import static cn.timebank.proposal.domain.ProposalStatus.DRAFT;
import static cn.timebank.proposal.domain.ProposalStatus.REJECTED;
import static cn.timebank.proposal.domain.ProposalStatus.SUBMITTED;
import static cn.timebank.proposal.domain.ProposalStatus.UNDER_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B4.9's exit criterion, unit-tested in isolation — copies {@code ServiceRecordStateMachineTest}'s shape. */
class ProposalStateMachineTest {

    @Test
    void ownerSubmitsADraft() {
        assertThat(ProposalStateMachine.canTransition(DRAFT, SUBMITTED, OWNER)).isTrue();
    }

    @Test
    void reviewerMovesSubmittedThroughUnderReviewToAnyDecision() {
        assertThat(ProposalStateMachine.canTransition(SUBMITTED, UNDER_REVIEW, REVIEWER)).isTrue();
        assertThat(ProposalStateMachine.canTransition(UNDER_REVIEW, APPROVED, REVIEWER)).isTrue();
        assertThat(ProposalStateMachine.canTransition(UNDER_REVIEW, CHANGES_REQUESTED, REVIEWER)).isTrue();
        assertThat(ProposalStateMachine.canTransition(UNDER_REVIEW, REJECTED, REVIEWER)).isTrue();
    }

    @Test
    void thereIsNoDirectEdgeFromSubmittedToApproved() {
        assertThat(ProposalStateMachine.canTransition(SUBMITTED, APPROVED, REVIEWER)).isFalse();
    }

    @Test
    void changesRequestedCanOnlyGoBackToSubmitted() {
        assertThat(ProposalStateMachine.canTransition(CHANGES_REQUESTED, SUBMITTED, OWNER)).isTrue();
        assertThat(ProposalStateMachine.canTransition(CHANGES_REQUESTED, UNDER_REVIEW, REVIEWER)).isFalse();
    }

    @Test
    void ownerCanWithdrawBeforeReviewStarts() {
        assertThat(ProposalStateMachine.canTransition(SUBMITTED, DRAFT, OWNER)).isTrue();
        assertThat(ProposalStateMachine.canTransition(UNDER_REVIEW, DRAFT, OWNER)).isFalse();
    }

    @Test
    void transitionEntryPointThrowsOnAnIllegalMoveAndAppliesOnALegalOne() {
        EventProposal proposal = newDraft();
        assertThatThrownBy(() -> ProposalStateMachine.transition(proposal, APPROVED, OWNER))
                .isInstanceOf(ApiException.class);
        assertThat(proposal.getStatus()).isEqualTo(DRAFT);

        ProposalStateMachine.transition(proposal, SUBMITTED, OWNER);
        assertThat(proposal.getStatus()).isEqualTo(SUBMITTED);
    }

    private EventProposal newDraft() {
        Instant now = Instant.now();
        return new EventProposal("prop_test", "std_test", "grade_6", "class_6A", "Test Proposal", "cat_campus",
                now.plusSeconds(3600), now.plusSeconds(7200), 10, "usr_teacher", 100, now);
    }
}
