package cn.timebank.iam.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A login account. Roles are configuration (a real table), never a Java
 * enum (Design.md §5 watch-out) — this entity only knows the {@link Role}
 * rows it has been assigned, not any fixed vocabulary of role names.
 *
 * {@code studentId} has no foreign key: the `student` table does not exist
 * until B2. It is a plain opaque reference, exactly as the contract
 * requires IDs to be treated everywhere else.
 */
@Entity
@Table(name = "user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "username", length = 64, nullable = false, updatable = false)
    private String username;

    @Setter
    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Setter
    @Column(name = "display_name", length = 100, nullable = false)
    private String displayName;

    @Setter
    @Column(name = "email")
    private String email;

    @Setter
    @Column(name = "student_id", length = 32)
    private String studentId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private UserStatus status;

    @Setter
    @Column(name = "preferred_language", length = 8, nullable = false)
    private String preferredLanguage;

    @Setter
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Setter
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    public User(String id, String username, String passwordHash, String displayName,
                String email, String preferredLanguage, Instant now) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.email = email;
        this.status = UserStatus.ACTIVE;
        this.preferredLanguage = preferredLanguage;
        this.failedLoginAttempts = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void addRole(Role role) {
        roles.add(role);
    }

    public boolean isLocked() {
        return status == UserStatus.LOCKED || (lockedUntil != null && lockedUntil.isAfter(Instant.now()));
    }

    /**
     * The shortest idle timeout among the user's roles (contract §2.2), so a
     * user holding both a long-timeout and a short-timeout role gets the
     * more conservative one. No role name is ever compared here — see
     * {@code role.session_timeout_minutes} (B1 seed migration).
     */
    public int sessionTimeoutMinutes(int fallbackMinutes) {
        return roles.stream().mapToInt(Role::getSessionTimeoutMinutes).min().orElse(fallbackMinutes);
    }
}
