package cn.timebank.caseflow.domain;

import cn.timebank.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static cn.timebank.caseflow.domain.CaseStateMachine.ActorRole.OWNER;
import static cn.timebank.caseflow.domain.CaseStateMachine.ActorRole.REVIEWER;
import static cn.timebank.caseflow.domain.CaseStatus.DRAFT;
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

    /** One step, since issue 082 removed the unreachable IN_REVIEW hop. */
    @Test
    void reviewerDecidesDirectlyFromSubmitted() {
        assertThat(CaseStateMachine.canTransition(SUBMITTED, RESOLVED, REVIEWER)).isTrue();
        assertThat(CaseStateMachine.canTransition(SUBMITTED, NEEDS_INFORMATION, REVIEWER)).isTrue();
        assertThat(CaseStateMachine.canTransition(SUBMITTED, REJECTED, REVIEWER)).isTrue();
    }

    @Test
    void anOwnerCannotDecideTheirOwnCase() {
        assertThat(CaseStateMachine.canTransition(SUBMITTED, RESOLVED, OWNER)).isFalse();
        assertThat(CaseStateMachine.canTransition(SUBMITTED, REJECTED, OWNER)).isFalse();
    }

    /** Issue 081: a student can take a filed case back, but only before it is decided. */
    @Test
    void ownerWithdrawsBeforeADecisionButNotAfter() {
        assertThat(CaseStateMachine.canTransition(SUBMITTED, DRAFT, OWNER)).isTrue();
        assertThat(CaseStateMachine.canTransition(NEEDS_INFORMATION, DRAFT, OWNER)).isFalse();
        assertThat(CaseStateMachine.canTransition(RESOLVED, DRAFT, OWNER)).isFalse();
        // ...and a reviewer is not the one who withdraws a student's case.
        assertThat(CaseStateMachine.canTransition(SUBMITTED, DRAFT, REVIEWER)).isFalse();
    }

    @Test
    void needsInformationCanOnlyGoBackToSubmitted() {
        assertThat(CaseStateMachine.canTransition(NEEDS_INFORMATION, SUBMITTED, OWNER)).isTrue();
        assertThat(CaseStateMachine.canTransition(NEEDS_INFORMATION, RESOLVED, REVIEWER)).isFalse();
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
