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

    /** 07-RoleFinetune.md §2.4: how many users currently hold a role, to block deletion of an in-use role. */
    long countByRolesId(String roleId);

    /** {@code GET /users/directory} — capped by the caller's {@link Pageable}. */
    @Query("SELECT DISTINCT u FROM User u JOIN u.roles r WHERE r.code = :roleCode AND u.status = 'ACTIVE' "
            + "AND LOWER(u.displayName) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY u.displayName")
    List<User> searchActiveByRoleAndDisplayNameContaining(@Param("roleCode") String roleCode,
                                                           @Param("search") String search, Pageable pageable);

    /** Proposal-side {@code proposedSupervisorId} validation — must be an active holder of {@code roleCode}. */
    @Query("SELECT COUNT(u) > 0 FROM User u JOIN u.roles r WHERE u.id = :id AND r.code = :roleCode AND u.status = 'ACTIVE'")
    boolean existsActiveByIdAndRoleCode(@Param("id") String id, @Param("roleCode") String roleCode);

    /**
     * 07-RoleFinetune.md §2.2: permission-based version of {@link
     * #existsActiveByIdAndRoleCode} — proposal-supervisor eligibility keyed
     * to {@code eventProposals.review} instead of the literal {@code
     * teacher} role code, so it keeps working if {@code teacher} is ever
     * renamed or deleted in favor of a differently-named custom role.
     */
    @Query("SELECT COUNT(u) > 0 FROM User u JOIN u.roles r JOIN r.permissions p "
            + "WHERE u.id = :id AND p.code = :permissionCode AND u.status = 'ACTIVE'")
    boolean existsActiveByIdAndPermissionCode(@Param("id") String id, @Param("permissionCode") String permissionCode);

    /**
     * 07-RoleFinetune.md §2.10: every active user holding {@code permissionCode}, paired with
     * their single role code (§2.5's one-role invariant) — for callers that need to further
     * narrow the result by role (e.g. proposal-approval publish notifications: admin/sysadmin
     * included unconditionally, everyone else matched against their assigned grades).
     */
    @Query("SELECT u.id AS userId, r.code AS roleCode FROM User u JOIN u.roles r JOIN r.permissions p "
            + "WHERE p.code = :permissionCode AND u.status = 'ACTIVE'")
    List<ActiveUserRole> findActiveWithPermission(@Param("permissionCode") String permissionCode);

    interface ActiveUserRole {
        String getUserId();
        String getRoleCode();
    }
}
