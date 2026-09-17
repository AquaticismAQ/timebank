package cn.timebank.audit;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditLogQueryService;
import cn.timebank.audit.application.AuditService;
import cn.timebank.audit.application.dto.AuditLogDto;
import cn.timebank.audit.web.AuditLogController;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5.12's exit criteria: {@code GET /audit-logs} filters correctly by
 * resource and by actor against real MySQL, and invariant 10 (no admin API
 * can modify or delete an audit log row) holds structurally — {@link
 * AuditLogController} has no mutating mapping at all, not merely a missing
 * permission.
 */
class AuditLogQueryIT extends AbstractIntegrationTest {

    @Autowired
    private AuditService auditService;
    @Autowired
    private AuditLogQueryService auditLogQueryService;

    @Test
    void filtersByResourceTypeAndId() {
        String resourceId = "case_query_" + System.nanoTime();
        auditService.record(new AuditEntry("usr_actor_a", "cases.create", "case.create", "case", resourceId, null, null, null));
        auditService.record(new AuditEntry("usr_actor_b", "cases.create", "case.update", "case", resourceId, null, null, null));
        auditService.record(new AuditEntry("usr_actor_a", "cases.create", "case.create", "case", "case_other", null, null, null));

        Page<AuditLogDto> page = auditLogQueryService.search(null, null, "case", resourceId, null, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(a -> a.resourceId().equals(resourceId));
    }

    @Test
    void filtersByActorId() {
        String actorId = "usr_actor_" + System.nanoTime();
        auditService.record(new AuditEntry(actorId, "cases.create", "case.create", "case", "case_x", null, null, null));
        auditService.record(new AuditEntry(actorId, "cases.create", "case.create", "case", "case_y", null, null, null));

        Page<AuditLogDto> page = auditLogQueryService.search(null, null, null, null, actorId, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
        assertThat(page.getContent()).allMatch(a -> a.actorId().equals(actorId));
    }

    @Test
    void filtersByCreatedAtRange() {
        String resourceId = "case_range_" + System.nanoTime();
        Instant before = Instant.now().minusSeconds(5);
        auditService.record(new AuditEntry("usr_actor_c", "cases.create", "case.create", "case", resourceId, null, null, null));
        Instant after = Instant.now().plusSeconds(5);

        Page<AuditLogDto> inRange = auditLogQueryService.search(null, null, "case", resourceId, null, before, after, PageRequest.of(0, 10));
        Page<AuditLogDto> outOfRange = auditLogQueryService.search(null, null, "case", resourceId, null, after, null, PageRequest.of(0, 10));

        assertThat(inRange.getTotalElements()).isEqualTo(1);
        assertThat(outOfRange.getTotalElements()).isZero();
    }

    @Test
    void theControllerHasNoMutatingMappingAtAll() {
        Method[] methods = AuditLogController.class.getMethods();
        assertThat(Arrays.stream(methods).anyMatch(m -> m.isAnnotationPresent(PostMapping.class))).isFalse();
        assertThat(Arrays.stream(methods).anyMatch(m -> m.isAnnotationPresent(PatchMapping.class))).isFalse();
        assertThat(Arrays.stream(methods).anyMatch(m -> m.isAnnotationPresent(DeleteMapping.class))).isFalse();
    }
}
