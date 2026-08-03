package cn.timebank.servicerecord.domain;

import cn.timebank.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static cn.timebank.servicerecord.domain.ServiceRecordStateMachine.ActorRole.OWNER;
import static cn.timebank.servicerecord.domain.ServiceRecordStateMachine.ActorRole.REVIEWER;
import static cn.timebank.servicerecord.domain.ServiceRecordStatus.APPROVED;
import static cn.timebank.servicerecord.domain.ServiceRecordStatus.DRAFT;
import static cn.timebank.servicerecord.domain.ServiceRecordStatus.REJECTED;
import static cn.timebank.servicerecord.domain.ServiceRecordStatus.RETURNED;
import static cn.timebank.servicerecord.domain.ServiceRecordStatus.SUBMITTED;
import static cn.timebank.servicerecord.domain.ServiceRecordStatus.UNDER_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B3.2's exit criterion, unit-tested in isolation before anything depends
 * on it: every legal edge succeeds, every illegal one is rejected, and
 * actor legitimacy is checked per edge, not just per status pair.
 */
class ServiceRecordStateMachineTest {

    @Test
    void ownerMaySubmitADraft() {
        assertThat(ServiceRecordStateMachine.canTransition(DRAFT, SUBMITTED, OWNER)).isTrue();
    }

    @Test
    void reviewerMayNotSubmitOnTheOwnersBehalf() {
        assertThat(ServiceRecordStateMachine.canTransition(DRAFT, SUBMITTED, REVIEWER)).isFalse();
    }

    @Test
    void reviewerMovesSubmittedToUnderReviewThenToAnyDecision() {
        assertThat(ServiceRecordStateMachine.canTransition(SUBMITTED, UNDER_REVIEW, REVIEWER)).isTrue();
        assertThat(ServiceRecordStateMachine.canTransition(UNDER_REVIEW, APPROVED, REVIEWER)).isTrue();
        assertThat(ServiceRecordStateMachine.canTransition(UNDER_REVIEW, RETURNED, REVIEWER)).isTrue();
        assertThat(ServiceRecordStateMachine.canTransition(UNDER_REVIEW, REJECTED, REVIEWER)).isTrue();
    }

    @Test
    void thereIsNoDirectEdgeFromSubmittedToApproved() {
        assertThat(ServiceRecordStateMachine.canTransition(SUBMITTED, APPROVED, REVIEWER)).isFalse();
    }

    @Test
    void ownerMayWithdrawOnlyBeforeReviewStarts() {
        assertThat(ServiceRecordStateMachine.canTransition(SUBMITTED, DRAFT, OWNER)).isTrue();
        assertThat(ServiceRecordStateMachine.canTransition(UNDER_REVIEW, DRAFT, OWNER)).isFalse();
    }

    @Test
    void returnedRecordCanOnlyGoBackToSubmitted() {
        assertThat(ServiceRecordStateMachine.canTransition(RETURNED, SUBMITTED, OWNER)).isTrue();
        assertThat(ServiceRecordStateMachine.canTransition(RETURNED, UNDER_REVIEW, REVIEWER)).isFalse();
    }

    @Test
    void anApprovedOrRejectedRecordIsTerminalForOrdinaryTransitions() {
        assertThat(ServiceRecordStateMachine.canTransition(APPROVED, SUBMITTED, OWNER)).isFalse();
        assertThat(ServiceRecordStateMachine.canTransition(REJECTED, SUBMITTED, OWNER)).isFalse();
    }

    @Test
    void transitionEntryPointThrowsApiExceptionOnAnIllegalMove() {
        ServiceRecord record = newDraft();
        assertThatThrownBy(() -> ServiceRecordStateMachine.transition(record, APPROVED, OWNER))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Cannot move");
        assertThat(record.getStatus()).isEqualTo(DRAFT);
    }

    @Test
    void transitionEntryPointAppliesTheStatusOnALegalMove() {
        ServiceRecord record = newDraft();
        ServiceRecordStateMachine.transition(record, SUBMITTED, OWNER);
        assertThat(record.getStatus()).isEqualTo(SUBMITTED);
    }

    private ServiceRecord newDraft() {
        return new ServiceRecord("rec_test", "std_test", "Test Student", "grade_6", "class_6A",
                SourceType.SELF, java.time.LocalDate.now(), java.time.Instant.now().minusSeconds(3600),
                java.time.Instant.now(), 60, "cat_campus", java.time.Instant.now());
    }
}
