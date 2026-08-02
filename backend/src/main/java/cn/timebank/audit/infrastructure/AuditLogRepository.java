package cn.timebank.audit.infrastructure;

import cn.timebank.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Deliberately extends the bare {@link Repository} marker, not
 * {@code JpaRepository} or {@code CrudRepository} — so there is no
 * {@code deleteById}, no {@code delete}, no derivable update method.
 * {@link #save} is present for a first insert only; the module never calls
 * it twice for the same id (invariant 10, Design.md §12).
 */
public interface AuditLogRepository extends Repository<AuditLog, String> {

    AuditLog save(AuditLog auditLog);

    Optional<AuditLog> findById(String id);

    Page<AuditLog> findByResourceTypeAndResourceId(String resourceType, String resourceId, Pageable pageable);

    Page<AuditLog> findByActorId(String actorId, Pageable pageable);

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
