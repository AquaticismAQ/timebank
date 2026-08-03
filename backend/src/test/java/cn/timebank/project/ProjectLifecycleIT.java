package cn.timebank.project;

import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4's exit criteria: action endpoints only for status (a `PATCH` cannot
 * carry `status` at all — {@code UpdateProjectRequest} has no such field,
 * a compile-time guarantee, not merely a runtime check), and time-derived
 * statuses sync lazily on read/action access.
 */
class ProjectLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    private ProjectService projectService;

    @Test
    void publishCloseArchiveMoveThroughTheStateMachineOnly() {
        ProjectDto draft = createDraft(Instant.now().minusSeconds(10), Instant.now().plusSeconds(1000));
        assertThat(draft.status()).isEqualTo("DRAFT");

        ProjectDto published = projectService.publish(draft.id(), draft.version(), "usr_test_admin", "zh-CN");
        assertThat(published.status()).isEqualTo("PUBLISHED");

        ProjectDto closed = projectService.close(published.id(), published.version(), "usr_test_admin", "zh-CN");
        assertThat(closed.status()).isEqualTo("CLOSED");

        ProjectDto archived = projectService.archive(closed.id(), closed.version(), "usr_test_admin", "zh-CN");
        assertThat(archived.status()).isEqualTo("ARCHIVED");
    }

    @Test
    void archivingADraftDirectlyIsRejected() {
        ProjectDto draft = createDraft(Instant.now().plusSeconds(1000), Instant.now().plusSeconds(2000));
        assertThatThrownBy(() -> projectService.archive(draft.id(), draft.version(), "usr_test_admin", "zh-CN"))
                .isInstanceOf(cn.timebank.common.error.ApiException.class);
    }

    @Test
    void registrationOpenStatusSyncsLazilyOnceItsTimeHasPassed() {
        // registrationOpenAt already in the past at creation time.
        ProjectDto draft = createDraft(Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600));
        ProjectDto published = projectService.publish(draft.id(), draft.version(), "usr_test_admin", "zh-CN");
        assertThat(published.status()).isEqualTo("PUBLISHED");

        ProjectDto reread = projectService.getDetail(published.id(), adminAuth(), "zh-CN");
        assertThat(reread.status()).isEqualTo("REGISTRATION_OPEN");

        // The version returned must reflect the sync-triggered increment,
        // not a pre-flush stale value — otherwise a caller chaining this
        // version into publish/close/archive would hit a spurious conflict.
        ProjectDto closed = projectService.close(reread.id(), reread.version(), "usr_test_admin", "zh-CN");
        assertThat(closed.status()).isEqualTo("CLOSED");
    }

    private ProjectDto createDraft(Instant registrationOpenAt, Instant registrationCloseAt) {
        Instant start = Instant.now().plus(5, ChronoUnit.DAYS);
        Instant end = start.plusSeconds(7200);
        CreateProjectRequest request = new CreateProjectRequest(
                "Test Project", "A summary", "A description", "cat_campus", null,
                start, end, "Main Hall", 10, 100, null, null, registrationOpenAt, registrationCloseAt);
        return projectService.createDraft(request, "usr_test_admin", "zh-CN");
    }

    private Authentication adminAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
