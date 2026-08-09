package cn.timebank.iam.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * `PATCH /users/{id}` — status/role changes, never a password field
 * (contract §13.4 general rule). {@code scopeGradeIds}: {@code null} means
 * "don't touch the existing grade bond," a non-null set (including empty)
 * replaces it outright — same partial-update convention as every other
 * field here, just meaningful for an empty collection too. {@code
 * studentId}/{@code staffId} follow the same null-means-untouched
 * convention (issue 003) — see {@code CreateUserRequest} for what each means.
 */
public record UpdateUserRequest(
        @Size(max = 100) String displayName,
        @Email String email,
        String status,
        Set<String> roleCodes,
        @NotNull Long version,
        Set<String> scopeGradeIds,
        String studentId,
        String staffId) {

    public UpdateUserRequest(String displayName, String email, String status, Set<String> roleCodes,
                              Long version, Set<String> scopeGradeIds) {
        this(displayName, email, status, roleCodes, version, scopeGradeIds, null, null);
    }

    public UpdateUserRequest(String displayName, String email, String status, Set<String> roleCodes, Long version) {
        this(displayName, email, status, roleCodes, version, null, null, null);
    }
}
