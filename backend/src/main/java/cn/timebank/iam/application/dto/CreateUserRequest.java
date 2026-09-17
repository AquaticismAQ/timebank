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
 *
 * <p><b>There is no {@code initialPassword} field</b> (issue 096 §3/§4). It
 * carried one until 2026-09-17, which meant an administrator invented a
 * password, knew it, and had to convey it over a channel nobody controls —
 * relocating the "初始密码清单" that
 * {@code 08-mlps-level1-security-design-guide.md} §8.2 promises schools does
 * not exist into the administrator's notes. An account created here is
 * {@code PENDING_ACTIVATION} with no stored hash, and the caller prints an
 * activation slip for it exactly as a class batch does — one credential
 * mechanism for every role, no exception for staff.</p>
 *
 * <p>{@code username} is now explicit rather than derived from {@code email}.
 * The form used to send {@code email.split("@")[0]}, which is why the API's
 * genuinely optional {@code email} was marked required in the UI — and why a
 * teacher who changed their address kept a username that no longer resembled
 * it.</p>
 */
public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(max = 100) String displayName,
        @Email String email,
        @NotBlank String roleCode,
        String preferredLanguage,
        Set<String> scopeGradeIds,
        String studentId,
        String staffId) {

    /** Convenience overload for existing callers that don't set an initial grade bond, studentId, or staffId. */
    public CreateUserRequest(String username, String displayName, String email,
                              String roleCode, String preferredLanguage, Set<String> scopeGradeIds) {
        this(username, displayName, email, roleCode, preferredLanguage, scopeGradeIds, null, null);
    }

    /** Convenience overload for the many existing callers that set none of the above. */
    public CreateUserRequest(String username, String displayName, String email,
                              String roleCode, String preferredLanguage) {
        this(username, displayName, email, roleCode, preferredLanguage, Set.of(), null, null);
    }
}
