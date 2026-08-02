package cn.timebank.iam.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A configurable role — code, display name, session timeout, and a set of
 * permissions. Never a Java enum (Design.md §5 watch-out): the four seeded
 * defaults (student/teacher/admin/sysadmin) are data, not a fixed
 * vocabulary the code depends on.
 */
@Entity
@Table(name = "role")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Setter
    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Setter
    @Column(name = "session_timeout_minutes", nullable = false)
    private int sessionTimeoutMinutes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    public Role(String id, String code, String name, int sessionTimeoutMinutes, Instant now) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
