package cn.timebank.caseflow.domain;

import cn.timebank.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static cn.timebank.caseflow.domain.CaseStateMachine.ActorRole.OWNER;
import static cn.timebank.caseflow.domain.CaseStateMachine.ActorRole.REVIEWER;
import static cn.timebank.caseflow.domain.CaseStatus.DRAFT;
import static cn.timebank.caseflow.domain.CaseStatus.IN_REVIEW;
import static cn.timebank.caseflow.domain.CaseStatus.NEEDS_INFORMATION;
import static cn.timebank.caseflow.domain.CaseStatus.REJECTED;
import static cn.timebank.caseflow.domain.CaseStatus.RESOLVED;
import static cn.timebank.caseflow.domain.CaseStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B5.1's exit criterion, unit-tested in isolation — copies {@code ProposalStateMachineTest}'s shape. */
class CaseStateMachineTest {

    @Test
    void ownerSubmitsADraft() {
        assertThat(CaseStateMachine.canTransition(DRAFT, SUBMITTED, OWNER)).isTrue();
    }

    @Test
    void reviewerMovesSubmittedThroughInReviewToAnyDecision() {
        assertThat(CaseStateMachine.canTransition(SUBMITTED, IN_REVIEW, REVIEWER)).isTrue();
        assertThat(CaseStateMachine.canTransition(IN_REVIEW, RESOLVED, REVIEWER)).isTrue();
        assertThat(CaseStateMachine.canTransition(IN_REVIEW, NEEDS_INFORMATION, REVIEWER)).isTrue();
        assertThat(CaseStateMachine.canTransition(IN_REVIEW, REJECTED, REVIEWER)).isTrue();
    }

    @Test
    void thereIsNoDirectEdgeFromSubmittedToResolved() {
        assertThat(CaseStateMachine.canTransition(SUBMITTED, RESOLVED, REVIEWER)).isFalse();
    }

    @Test
    void needsInformationCanOnlyGoBackToSubmitted() {
        assertThat(CaseStateMachine.canTransition(NEEDS_INFORMATION, SUBMITTED, OWNER)).isTrue();
        assertThat(CaseStateMachine.canTransition(NEEDS_INFORMATION, IN_REVIEW, REVIEWER)).isFalse();
    }

    @Test
    void transitionEntryPointThrowsOnAnIllegalMoveAndAppliesOnALegalOne() {
        Case c = newDraft();
        assertThatThrownBy(() -> CaseStateMachine.transition(c, RESOLVED, OWNER))
                .isInstanceOf(ApiException.class);
        assertThat(c.getStatus()).isEqualTo(DRAFT);

        CaseStateMachine.transition(c, SUBMITTED, OWNER);
        assertThat(c.getStatus()).isEqualTo(SUBMITTED);
    }

    private Case newDraft() {
        return new Case("case_test", CaseType.APPEAL, "std_test", "grade_6", "class_6A", "Test Case",
                "TEST_REASON", Instant.now());
    }
}
