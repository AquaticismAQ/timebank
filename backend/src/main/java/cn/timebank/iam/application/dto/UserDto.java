package cn.timebank.iam.application.dto;

import java.time.Instant;
import java.util.Set;

/**
 * Response shape for `GET /users`, `/users/{id}`. Never carries a password
 * or password hash — MOCK_USERS' `password` field is exactly the shortcut
 * contract §13.3 says not to copy. {@code scopeGradeIds} is always present
 * (empty for a non-teacher, or a teacher with no grade bond yet).
 *
 * {@code roleCode} is a single value, not a set — 07-RoleFinetune.md §2.5,
 * every user holds exactly one role.
 *
 * <p><b>{@code gradeLevel}/{@code classId}/{@code className} are a
 * student's own placement, and are not {@code scopeGradeIds}</b> (issue
 * 094). The two are routinely confused because both answer "which grade?":
 * {@code scopeGradeIds} is a <em>staff</em> account's grade bond — the
 * grades a teacher may see — and is empty for a student, while these three
 * come from the {@code student} row {@code studentId} points at and are
 * null for every non-student account. Neither is ever a fallback for the
 * other.</p>
 */
public record UserDto(
        String id,
        String username,
        String displayName,
        String email,
        String studentId,
        String staffId,
        String status,
        String preferredLanguage,
        String roleCode,
        Long version,
        Instant createdAt,
        Instant updatedAt,
        Set<String> scopeGradeIds,
        String gradeId,
        Integer gradeLevel,
        String classId,
        String className,
        /**
         * Issue 094 §7. There is no {@code user.last_login} column — this is the
         * newest successful {@code login_log} row. Populated by the list read
         * model, where the column is displayed; {@code null} on single-user
         * responses rather than paying a second query for a screen that has
         * nowhere to show it.
         */
        Instant lastLoginAt) {

    /**
     * {@code UserServiceImpl} (module {@code iam}) can't populate this
     * field itself — {@code security.application} already depends on
     * {@code iam.application} (`UserDetailsServiceImpl`), so the reverse
     * dependency would cycle the two modules' application layers. The web
     * layer enriches the response with this copy helper instead, the same
     * place {@code SessionRevocationService} is called from for the same
     * reason (see {@code UserServiceImpl}'s class Javadoc).
     *
     * <p>{@code gradeId}/{@code gradeLevel}/{@code classId}/{@code
     * className} deliberately have <em>no</em> equivalent web-layer helper:
     * {@code student} does not depend on {@code iam} in either layer (it
     * reaches {@code security.application} and {@code audit}, nothing
     * else), so {@code iam.application -> student.application} adds no
     * cycle — {@code UserServiceImpl} already holds a {@code StudentService}
     * and simply populates them itself. The {@code scopeGradeIds} detour
     * exists because of {@code security}'s back-edge specifically, not
     * because enriching in the web layer is the house style.</p>
     */
    public UserDto withScopeGradeIds(Set<String> scopeGradeIds) {
        return new UserDto(id, username, displayName, email, studentId, staffId, status, preferredLanguage, roleCode,
                version, createdAt, updatedAt, scopeGradeIds, gradeId, gradeLevel, classId, className, lastLoginAt);
    }
}
