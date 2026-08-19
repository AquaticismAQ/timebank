package cn.timebank.iam.application;

import cn.timebank.iam.application.dto.AuthDtos;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.MeResponse;
import cn.timebank.iam.application.dto.UpdateProfileRequest;
import cn.timebank.iam.application.dto.UpdateUserRequest;
import cn.timebank.iam.application.dto.UserDirectoryEntryDto;
import cn.timebank.iam.application.dto.UserDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The `iam` module's published interface (Design.md §6). */
public interface UserService {

    Optional<AuthenticatedUserView> findByUsernameForAuth(String username);

    UserDto getById(String id);

    Page<UserDto> list(Pageable pageable);

    UserDto create(CreateUserRequest request, String actorId);

    UserDto update(String id, UpdateUserRequest request, String actorId);

    UserDto updateProfile(String id, UpdateProfileRequest request);

    void unlock(String id, String actorId);

    AuthDtos.AdminResetPasswordResponse adminResetPassword(String id, String actorId);

    void changeOwnPassword(String userId, String currentPassword, String newPassword);

    /** @return the affected user's username, so the caller can revoke live sessions */
    String resetPasswordWithToken(String userId, String newPassword);

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
}
