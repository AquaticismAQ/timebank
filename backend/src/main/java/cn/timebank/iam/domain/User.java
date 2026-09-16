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
 * {@code studentId} has no foreign key — {@code iam} validates its existence
 * via {@code student}'s own application-layer service rather than a
 * cross-module FK, the same modular-monolith boundary B0.2's ArchUnit rules
 * enforce elsewhere. It is a plain opaque reference, exactly as the contract
 * requires IDs to be treated everywhere else. {@code staffId} (issue 003)
 * follows the identical pattern for teacher/admin accounts, with no
 * referenced table to have a foreign key to in the first place.
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

    /**
     * Nullable since V27 (10-StudentIdentityAndOnboarding.md §2.4/F-5), and
     * null is a meaningful state rather than an absent optional: an imported
     * student has no password until they choose one at activation, and "no
     * password stored" is precisely what makes the account impossible to log
     * into in the meantime. Never set from a server-generated password —
     * §2.4 removes server-side password generation for students entirely.
     */
    @Setter
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /**
     * §3.1/F-7. Written by the activation and admin-reset paths (§3.5/§3.7);
     * false for an account that chose its own password, which is every
     * account that existed before V27.
     */
    @Setter
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Setter
    @Column(name = "display_name", length = 100, nullable = false)
    private String displayName;

    @Setter
    @Column(name = "email")
    private String email;

    @Setter
    @Column(name = "student_id", length = 32)
    private String studentId;

    /** Issue 003: required+unique for teacher/admin accounts, never set for student accounts. */
    @Setter
    @Column(name = "staff_id", length = 64)
    private String staffId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
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

    /**
     * A freshly imported student's account (§3.3): no password, and pending
     * until the activation code on their printed slip is redeemed. Inert by
     * construction rather than by a status check someone can forget —
     * there is no credential to present.
     */
    public static User pendingActivation(String id, String username, String displayName,
                                         String studentId, String preferredLanguage, Instant now) {
        User user = new User(id, username, null, displayName, null, preferredLanguage, now);
        user.status = UserStatus.PENDING_ACTIVATION;
        user.studentId = studentId;
        return user;
    }

    /**
     * Whether this account can ever satisfy a password check. False for a
     * never-activated import and for anything else without a stored hash —
     * the authentication path must treat those as unusable rather than
     * reaching the encoder with a null hash.
     */
    public boolean hasUsableCredential() {
        return passwordHash != null;
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
