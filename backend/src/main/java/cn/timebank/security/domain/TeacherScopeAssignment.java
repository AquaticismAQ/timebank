package cn.timebank.security.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One row of "this principal may see this resource type for this grade,
 * class, or project" (B1.7). An empty set of assignments for a teacher and
 * resource type is a valid, ordinary state — not misconfiguration, not
 * `ALL`. Values (`scopeValue`) are opaque identifiers, never parsed.
 */
@Entity
@Table(name = "teacher_scope_assignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeacherScopeAssignment {

    public enum ScopeType { GRADE, CLASS, PROJECT }

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 32, nullable = false, updatable = false)
    private String userId;

    @Column(name = "resource_type", length = 32, nullable = false, updatable = false)
    private String resourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", length = 16, nullable = false, updatable = false)
    private ScopeType scopeType;

    @Column(name = "scope_value", length = 64, nullable = false, updatable = false)
    private String scopeValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TeacherScopeAssignment(String id, String userId, String resourceType,
                                   ScopeType scopeType, String scopeValue, Instant now) {
        this.id = id;
        this.userId = userId;
        this.resourceType = resourceType;
        this.scopeType = scopeType;
        this.scopeValue = scopeValue;
        this.createdAt = now;
    }
}
