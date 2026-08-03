package cn.timebank.project.domain;

import cn.timebank.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static cn.timebank.project.domain.ProjectStateMachine.ActorRole.MANAGER;
import static cn.timebank.project.domain.ProjectStateMachine.ActorRole.SYSTEM;
import static cn.timebank.project.domain.ProjectStatus.ARCHIVED;
import static cn.timebank.project.domain.ProjectStatus.CLOSED;
import static cn.timebank.project.domain.ProjectStatus.COMPLETED;
import static cn.timebank.project.domain.ProjectStatus.DRAFT;
import static cn.timebank.project.domain.ProjectStatus.IN_PROGRESS;
import static cn.timebank.project.domain.ProjectStatus.PUBLISHED;
import static cn.timebank.project.domain.ProjectStatus.REGISTRATION_OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B4.2's exit criterion, unit-tested in isolation — copies {@code ServiceRecordStateMachineTest}'s shape. */
class ProjectStateMachineTest {

    @Test
    void managerPublishesADraft() {
        assertThat(ProjectStateMachine.canTransition(DRAFT, PUBLISHED, MANAGER)).isTrue();
    }

    @Test
    void systemMovesThroughTheTimeDerivedStatusesButManagerCannot() {
        assertThat(ProjectStateMachine.canTransition(PUBLISHED, REGISTRATION_OPEN, SYSTEM)).isTrue();
        assertThat(ProjectStateMachine.canTransition(PUBLISHED, REGISTRATION_OPEN, MANAGER)).isFalse();
        assertThat(ProjectStateMachine.canTransition(REGISTRATION_OPEN, IN_PROGRESS, SYSTEM)).isTrue();
        assertThat(ProjectStateMachine.canTransition(IN_PROGRESS, COMPLETED, SYSTEM)).isTrue();
    }

    @Test
    void managerCanCloseFromAnyActiveStatus() {
        assertThat(ProjectStateMachine.canTransition(PUBLISHED, CLOSED, MANAGER)).isTrue();
        assertThat(ProjectStateMachine.canTransition(REGISTRATION_OPEN, CLOSED, MANAGER)).isTrue();
        assertThat(ProjectStateMachine.canTransition(IN_PROGRESS, CLOSED, MANAGER)).isTrue();
    }

    @Test
    void onlyCompletedOrClosedCanBeArchived() {
        assertThat(ProjectStateMachine.canTransition(COMPLETED, ARCHIVED, MANAGER)).isTrue();
        assertThat(ProjectStateMachine.canTransition(CLOSED, ARCHIVED, MANAGER)).isTrue();
        assertThat(ProjectStateMachine.canTransition(DRAFT, ARCHIVED, MANAGER)).isFalse();
    }

    @Test
    void transitionEntryPointThrowsOnAnIllegalMoveAndAppliesOnALegalOne() {
        Project project = newDraft();
        assertThatThrownBy(() -> ProjectStateMachine.transition(project, ARCHIVED, MANAGER))
                .isInstanceOf(ApiException.class);
        assertThat(project.getStatus()).isEqualTo(DRAFT);

        ProjectStateMachine.transition(project, PUBLISHED, MANAGER);
        assertThat(project.getStatus()).isEqualTo(PUBLISHED);
    }

    private Project newDraft() {
        Instant now = Instant.now();
        return new Project("proj_test", "Test Project", "cat_campus", "usr_owner", null,
                now.plusSeconds(3600), now.plusSeconds(7200), 10, 100, now, now.plusSeconds(1800), now);
    }
}
