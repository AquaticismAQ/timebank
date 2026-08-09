package cn.timebank.iam.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * {@code scopeGradeIds} is only meaningful when {@code roleCodes} contains
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
 */
public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(min = 8, max = 128) String initialPassword,
        @NotBlank @Size(max = 100) String displayName,
        @Email String email,
        @NotEmpty Set<String> roleCodes,
        String preferredLanguage,
        Set<String> scopeGradeIds,
        String studentId,
        String staffId) {

    /** Convenience overload for existing callers that don't set an initial grade bond, studentId, or staffId. */
    public CreateUserRequest(String username, String initialPassword, String displayName, String email,
                              Set<String> roleCodes, String preferredLanguage, Set<String> scopeGradeIds) {
        this(username, initialPassword, displayName, email, roleCodes, preferredLanguage, scopeGradeIds, null, null);
    }

    /** Convenience overload for the many existing callers that set none of the above. */
    public CreateUserRequest(String username, String initialPassword, String displayName, String email,
                              Set<String> roleCodes, String preferredLanguage) {
        this(username, initialPassword, displayName, email, roleCodes, preferredLanguage, Set.of(), null, null);
    }
}
