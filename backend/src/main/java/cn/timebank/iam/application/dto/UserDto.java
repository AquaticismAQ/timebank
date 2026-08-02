package cn.timebank.iam.application.dto;

import java.time.Instant;
import java.util.Set;

/**
 * Response shape for `GET /users`, `/users/{id}`. Never carries a password
 * or password hash — MOCK_USERS' `password` field is exactly the shortcut
 * contract §13.3 says not to copy.
 */
public record UserDto(
        String id,
        String username,
        String displayName,
        String email,
        String studentId,
        String status,
        String preferredLanguage,
        Set<String> roleCodes,
        Long version,
        Instant createdAt,
        Instant updatedAt) {
}
