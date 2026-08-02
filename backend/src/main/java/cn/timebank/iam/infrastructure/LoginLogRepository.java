package cn.timebank.iam.infrastructure;

import cn.timebank.iam.domain.LoginLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Extends the bare {@link Repository} marker, not {@code JpaRepository} —
 * same structural append-only guarantee as {@code AuditLogRepository}
 * (B0.8): no delete, no update method exists to call.
 */
public interface LoginLogRepository extends Repository<LoginLog, String> {

    LoginLog save(LoginLog loginLog);

    Page<LoginLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<LoginLog> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
