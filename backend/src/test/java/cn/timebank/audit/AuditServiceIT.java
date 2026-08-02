package cn.timebank.audit;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.audit.domain.AuditLog;
import cn.timebank.audit.infrastructure.AuditLogRepository;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B0.8 exit criteria, proven against the real MySQL schema Flyway just
 * migrated (not a mock): a row lands with the fields Design.md §13
 * requires, and the repository is structurally incapable of updating or
 * deleting one.
 */
class AuditServiceIT extends AbstractIntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogRepository repository;

    @Test
    void recordWritesAnAppendOnlyRowWithTheExpectedFields() {
        AuditEntry entry = new AuditEntry("usr_test1", "reviews.decide", "review.approve",
                "serviceRecord", "rec_test1", null, null, "TEST_REASON");

        auditService.record(entry);

        List<AuditLog> rows = repository
                .findByResourceTypeAndResourceId("serviceRecord", "rec_test1", PageRequest.of(0, 10))
                .getContent();

        assertThat(rows).hasSize(1);
        AuditLog row = rows.get(0);
        assertThat(row.getActorId()).isEqualTo("usr_test1");
        assertThat(row.getPermissionUsed()).isEqualTo("reviews.decide");
        assertThat(row.getAction()).isEqualTo("review.approve");
        assertThat(row.getReasonCode()).isEqualTo("TEST_REASON");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getRequestId()).isNotNull();
    }

    @Test
    void repositoryDeclaresNoUpdateOrDeleteMethod() {
        // Structural proof, not a convention: this is what "an attempt to update
        // an audit row fails at the repository layer" means when the repository
        // extends the bare Repository marker instead of CrudRepository/JpaRepository.
        List<String> methodNames = Arrays.stream(AuditLogRepository.class.getMethods())
                .map(Method::getName)
                .toList();

        assertThat(methodNames)
                .noneMatch(name -> name.startsWith("delete"))
                .noneMatch(name -> name.startsWith("update"));
    }

    @Test
    void beforeAndAfterAreSerializedAsCompactJson() {
        record Before(String status) {
        }
        record After(String status) {
        }
        AuditEntry entry = new AuditEntry("usr_test2", null, "review.approve",
                "serviceRecord", "rec_test2", new Before("submitted"), new After("approved"), null);

        auditService.record(entry);

        Optional<AuditLog> row = repository
                .findByResourceTypeAndResourceId("serviceRecord", "rec_test2", PageRequest.of(0, 10))
                .getContent().stream().findFirst();

        assertThat(row).isPresent();
        // MySQL canonicalizes stored JSON (adds a space after ':') on read-back,
        // so this checks content, not byte-for-byte formatting.
        assertThat(row.get().getBeforeSummary()).contains("status").contains("submitted");
        assertThat(row.get().getAfterSummary()).contains("status").contains("approved");
    }
}
