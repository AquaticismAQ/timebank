package cn.timebank.iam.application;

import cn.timebank.iam.application.dto.AuthDtos;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.MeResponse;
import cn.timebank.iam.application.dto.UpdateProfileRequest;
import cn.timebank.iam.application.dto.UpdateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
}
