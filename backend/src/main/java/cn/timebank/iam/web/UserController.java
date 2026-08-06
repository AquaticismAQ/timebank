package cn.timebank.iam.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthDtos.AdminResetPasswordResponse;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UpdateUserRequest;
import cn.timebank.iam.application.dto.UserDirectoryEntryDto;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.SessionRevocationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Contract §4.15: `/users`. */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Set<String> SORTABLE = Set.of("id", "username", "displayName", "createdAt");

    private final UserService userService;
    private final SessionRevocationService sessionRevocationService;

    public UserController(UserService userService, SessionRevocationService sessionRevocationService) {
        this.userService = userService;
        this.sessionRevocationService = sessionRevocationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('users.read')")
    public ApiResponse<List<UserDto>> list(PageQuery query) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<UserDto> page = userService.list(pageable);
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    /**
     * Contract §4.15: a small, capped, name+role-matched search for a
     * proposal supervisor picker — {@code directory.read} is what
     * {@code role_student} actually holds. No path-precedence concern here:
     * unlike {@code StudentController}, this class has no {@code GET
     * /users/{id}} to collide with.
     */
    @GetMapping("/directory")
    @PreAuthorize("hasAuthority('users.read') or hasAuthority('directory.read')")
    public ApiResponse<List<UserDirectoryEntryDto>> directory(
            @RequestParam String role, @RequestParam(required = false) String search) {
        return ApiResponse.of(userService.searchDirectory(role, search));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<UserDto> create(@Valid @RequestBody CreateUserRequest request, Authentication auth) {
        String actorId = principalId(auth);
        return ApiResponse.of(userService.create(request, actorId));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<UserDto> update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request,
                                        Authentication auth) {
        UserDto before = userService.getById(id);
        UserDto updated = userService.update(id, request, principalId(auth));
        if ("INACTIVE".equals(request.status()) && !"INACTIVE".equals(before.status())) {
            sessionRevocationService.revokeAllSessionsForUser(updated.username());
        }
        return ApiResponse.of(updated);
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<Void> unlock(@PathVariable String id, Authentication auth) {
        userService.unlock(id, principalId(auth));
        return ApiResponse.of(null);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<AdminResetPasswordResponse> resetPassword(@PathVariable String id, Authentication auth) {
        AdminResetPasswordResponse result = userService.adminResetPassword(id, principalId(auth));
        sessionRevocationService.revokeAllSessionsForUser(result.username());
        return ApiResponse.of(result);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
