package cn.timebank.iam.application;

import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.MeResponse;
import cn.timebank.iam.application.dto.UpdateProfileRequest;
import cn.timebank.iam.application.dto.UpdateUserRequest;
import cn.timebank.iam.application.dto.UserDirectoryEntryDto;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.application.dto.UserListFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The `iam` module's published interface (Design.md §6). */
public interface UserService {

    Optional<AuthenticatedUserView> findByUsernameForAuth(String username);

    UserDto getById(String id);

    /**
     * Issue 094. {@code filter} narrows by display name, role code, account
     * status, and the linked student's grade/class; {@code pageable}'s sort
     * is restricted to {@code UserController.SORTABLE}, which includes
     * {@code gradeLevel} and {@code className}. Every row carries the
     * student's own grade/class placement (null for staff) — never the
     * account's {@code scopeGradeIds}, which the web layer adds separately.
     */
    Page<UserDto> list(Pageable pageable, UserListFilter filter);

    UserDto create(CreateUserRequest request, String actorId);

    /**
     * 10-StudentIdentityAndOnboarding.md §3.3: the account a bulk roster
     * import creates alongside the student row — an 8-digit username (§2.1),
     * no password, and {@code PENDING_ACTIVATION} status.
     *
     * <p>The account is <b>inert</b>: it cannot be logged into until the
     * student redeems the activation code on their printed slip and chooses
     * a password (§2.4). That is true by construction rather than by policy —
     * there is no stored credential to present.</p>
     *
     * <p>Idempotent per student: a re-run commit that reaches an already
     * provisioned student returns the existing account instead of minting a
     * second login for the same person.</p>
     */
    PendingStudentAccount createPendingStudentAccount(String studentId, String displayName,
                                                       String preferredLanguage, String actorId);

    record PendingStudentAccount(String userId, String username) {
    }

    UserDto update(String id, UpdateUserRequest request, String actorId);

    UserDto updateProfile(String id, UpdateProfileRequest request);

    void unlock(String id, String actorId);

    /**
     * 10-StudentIdentityAndOnboarding.md §3.7: the account half of an admin
     * password reset. <b>Clears the stored password and returns the account to
     * {@code PENDING_ACTIVATION}</b> — it does not set a new password, because
     * §2.4 removes server-side password generation entirely.
     *
     * <p>Both reasons a reset is ever asked for want the old password gone
     * rather than replaced: it was forgotten, so it is worthless, or the
     * account is compromised, so it must stop working this second. The account
     * is inert afterwards exactly as a freshly imported one is ({@link
     * #createPendingStudentAccount}) — {@code hasUsableCredential()} is false,
     * so it cannot be logged into until the caller's freshly issued activation
     * code is redeemed, and §3.6's sweep will move it to {@code
     * ACTIVATION_EXPIRED} if the slip is never used.
     *
     * <p>Callers are expected to issue an activation code in the same
     * transaction (see {@code CredentialSlipService.issueForReset}); this
     * method on its own leaves an account nobody can get into.</p>
     *
     * @throws cn.timebank.common.error.ApiException 409 for a self-reset, and
     *         409 for an {@code INACTIVE} account — see the implementation for
     *         why neither is merely an awkward case
     * @return enough to print the slip and revoke the account's live sessions
     */
    ResetAccount resetToPendingActivation(String id, String actorId);

    record ResetAccount(String userId, String username, String displayName) {
    }

    void changeOwnPassword(String userId, String currentPassword, String newPassword);

    // No token-driven reset method here (issue 089). The only two ways a
    // password is ever set are this one — by someone who proved they know the
    // current password — and redeeming an activation code, which an
    // administrator issued after identifying the person offline.

    void recordSuccessfulLogin(String userId);

    /** @return true if this failure just crossed the lockout threshold */
    boolean recordFailedLogin(String userId);

    MeResponse buildMeResponse(String userId);

    /**
     * B3.12: resolves which login account a student's in-app notification
     * goes to. Exposed here (rather than a direct repository reach-in) so
     * `notification`/`review` never depend on `iam.domain`/`infrastructure`
     * (Design.md §6 module-boundary rule).
     */
    Optional<String> findUserIdByStudentId(String studentId);

    /**
     * 10-StudentIdentityAndOnboarding.md §3.4: the batch form of {@link
     * #findUserIdByStudentId}, for assembling a class's credential slips
     * without one round trip per child.
     *
     * <p>Returns the {@code status} as well as the ids, because §2.7's
     * "regeneration spares activated students" is a <em>selection</em> rule the
     * caller applies in Java — see {@code ActivationService.issueAll}, which
     * deliberately applies no status filter of its own. Deciding it here, in
     * SQL, would bury the rule where only an integration test could reach it
     * and would also break §3.7's admin reset, which legitimately issues a code
     * for an already-active account.</p>
     *
     * <p>Student ids with no account are silently absent, the same convention
     * {@link #displayNamesOf} uses — callers use that to detect the roster rows
     * that were never provisioned a login (see §5's {@code POST /students} gap).</p>
     */
    List<StudentAccount> studentAccountsOf(List<String> studentIds);

    /** @param status a {@code UserStatus} name, e.g. {@code PENDING_ACTIVATION} */
    record StudentAccount(String studentId, String userId, String username, String status) {
    }

    /**
     * B4: denormalizing `ownerName`/`proposedSupervisorName`-style fields on
     * `project`/`eventProposal` lists without N+1 — a batch lookup, same
     * cross-module-without-domain-reach-in shape as {@link
     * #findUserIdByStudentId}. Ids absent from the result are silently
     * omitted (the caller decides the fallback, e.g. an empty string).
     */
    Map<String, String> displayNamesOf(List<String> userIds);

    /**
     * {@code GET /users/directory} — a small, capped, name-matched search
     * over active holders of {@code roleCode}, for a supervisor picker.
     * Blank {@code search} returns empty rather than every user with that
     * role; this is a typeahead, not a browse (contract §4.15).
     */
    List<UserDirectoryEntryDto> searchDirectory(String roleCode, String search);

    /**
     * Proposal-side {@code proposedSupervisorId} validation: does this id
     * belong to an active user holding {@code roleCode}? Contract §4.3 —
     * approval makes "the designated teacher" the generated project's
     * owner, so this must be checked before that id is ever trusted.
     */
    boolean hasActiveRole(String userId, String roleCode);

    /**
     * 07-RoleFinetune.md §2.2: same shape as {@link #hasActiveRole}, but
     * keyed to a permission code instead of a role code — used where a
     * literal role-code check would otherwise pin behavior to a role that
     * can now be renamed or deleted (e.g. {@code teacher}).
     */
    boolean hasActivePermission(String userId, String permissionCode);

    /**
     * 07-RoleFinetune.md §2.10: every active user holding {@code
     * permissionCode}, each paired with their single role code — e.g.
     * proposal-approval publish notifications need every {@code
     * projects.publish} holder plus enough information to tell whether a
     * given holder is school-wide (admin/sysadmin) or needs matching
     * against their assigned grades (everyone else).
     */
    List<UserPermissionHolder> activeUsersWithPermission(String permissionCode);

    record UserPermissionHolder(String userId, String roleCode) {
    }
}
