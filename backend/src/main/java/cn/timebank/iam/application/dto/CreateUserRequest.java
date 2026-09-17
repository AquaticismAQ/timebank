package cn.timebank.iam.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * {@code scopeGradeIds} is only meaningful when {@code roleCode} is
 * {@code teacher} — written as {@code teacher_scope_assignment} rows
 * (issue 002) when present, ignored for every other role.
 *
 * {@code studentId} (role {@code student}): must reference an existing,
 * not-already-linked {@link cn.timebank.student.domain.Student} row — issue
 * 003 deliberately does not let this form free-type a new grade/class/
 * student-number, since that would be a second, independently-editable copy
 * of data the roster (CSV-imported) already owns.
 *
 * {@code staffId} (role {@code teacher} or {@code admin}): a free-text,
 * unique identifier — no existing "staff roster" module to link against.
 *
 * Cross-field required-ness ("a teacher needs at least one grade," "a
 * student needs a linked studentId," etc.) is enforced in
 * {@code UserController}, not here or in {@code UserServiceImpl} — see
 * {@code UserController}'s class Javadoc for why real requests and
 * test-fixture service calls are deliberately held to different bars.
 *
 * {@code roleCode} is a single value, not a set — 07-RoleFinetune.md §2.5:
 * every user holds exactly one role, always. The type itself is the
 * cardinality enforcement; no separate "exactly one" check is needed.
 */
public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(min = 12, max = 128) String initialPassword,
        @NotBlank @Size(max = 100) String displayName,
        @Email String email,
        @NotBlank String roleCode,
        String preferredLanguage,
        Set<String> scopeGradeIds,
        String studentId,
        String staffId) {

    /** Convenience overload for existing callers that don't set an initial grade bond, studentId, or staffId. */
    public CreateUserRequest(String username, String initialPassword, String displayName, String email,
                              String roleCode, String preferredLanguage, Set<String> scopeGradeIds) {
        this(username, initialPassword, displayName, email, roleCode, preferredLanguage, scopeGradeIds, null, null);
    }

    /** Convenience overload for the many existing callers that set none of the above. */
    public CreateUserRequest(String username, String initialPassword, String displayName, String email,
                              String roleCode, String preferredLanguage) {
        this(username, initialPassword, displayName, email, roleCode, preferredLanguage, Set.of(), null, null);
    }
}
