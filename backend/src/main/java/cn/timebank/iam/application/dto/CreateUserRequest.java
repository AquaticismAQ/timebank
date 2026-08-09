package cn.timebank.iam.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * {@code scopeGradeIds} is only meaningful when {@code roleCodes} contains
 * {@code teacher} — written as {@code teacher_scope_assignment} rows
 * (issue 002) when present, ignored for every other role. Not yet
 * required at the API layer (issue 003's job — "at least one grade" is a
 * conditional cross-field rule, not a plain per-field constraint, and
 * many existing callers create a teacher without one on purpose).
 */
public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(min = 8, max = 128) String initialPassword,
        @NotBlank @Size(max = 100) String displayName,
        @Email String email,
        @NotEmpty Set<String> roleCodes,
        String preferredLanguage,
        Set<String> scopeGradeIds) {

    /** Convenience overload for the many existing callers that don't set an initial grade bond. */
    public CreateUserRequest(String username, String initialPassword, String displayName, String email,
                              Set<String> roleCodes, String preferredLanguage) {
        this(username, initialPassword, displayName, email, roleCodes, preferredLanguage, Set.of());
    }
}
