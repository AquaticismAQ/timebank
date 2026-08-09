package cn.timebank.iam.infrastructure;

import cn.timebank.iam.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<User> findByStudentId(String studentId);

    boolean existsByStudentId(String studentId);

    boolean existsByStudentIdAndIdNot(String studentId, String id);

    boolean existsByStaffId(String staffId);

    boolean existsByStaffIdAndIdNot(String staffId, String id);

    Page<User> findAll(Pageable pageable);

    /** {@code GET /users/directory} — capped by the caller's {@link Pageable}. */
    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r.code = :roleCode AND u.status = 'ACTIVE' "
            + "AND LOWER(u.displayName) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY u.displayName")
    List<User> searchActiveByRoleAndDisplayNameContaining(@Param("roleCode") String roleCode,
                                                           @Param("search") String search, Pageable pageable);

    /** Proposal-side {@code proposedSupervisorId} validation — must be an active holder of {@code roleCode}. */
    @Query("SELECT COUNT(u) > 0 FROM User u JOIN u.roles r WHERE u.id = :id AND r.code = :roleCode AND u.status = 'ACTIVE'")
    boolean existsActiveByIdAndRoleCode(@Param("id") String id, @Param("roleCode") String roleCode);
}
