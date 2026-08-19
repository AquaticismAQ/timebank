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
        Set<String> scopeGradeIds) {

    /**
     * {@code UserServiceImpl} (module {@code iam}) can't populate this
     * field itself — {@code security.application} already depends on
     * {@code iam.application} (`UserDetailsServiceImpl`), so the reverse
     * dependency would cycle the two modules' application layers. The web
     * layer enriches the response with this copy helper instead, the same
     * place {@code SessionRevocationService} is called from for the same
     * reason (see {@code UserServiceImpl}'s class Javadoc).
     */
    public UserDto withScopeGradeIds(Set<String> scopeGradeIds) {
        return new UserDto(id, username, displayName, email, studentId, staffId, status, preferredLanguage, roleCode,
                version, createdAt, updatedAt, scopeGradeIds);
    }
}
