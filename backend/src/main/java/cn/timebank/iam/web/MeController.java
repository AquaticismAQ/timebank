package cn.timebank.iam.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.MeResponse;
import cn.timebank.iam.application.dto.UpdateProfileRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.TeacherScopeAssignmentService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Contract §4.1, §2.2: `/me`, `/me/profile`. `scopeGradeIds` enrichment
 * (issue 004) happens here rather than in {@code UserServiceImpl} for the
 * same {@code iam}/{@code security} module-cycle reason documented on
 * {@code UserController} and {@code UserDto.withScopeGradeIds}.
 */
@RestController
public class MeController {

    private final UserService userService;
    private final TeacherScopeAssignmentService teacherScopeAssignmentService;

    public MeController(UserService userService, TeacherScopeAssignmentService teacherScopeAssignmentService) {
        this.userService = userService;
        this.teacherScopeAssignmentService = teacherScopeAssignmentService;
    }

    @GetMapping("/api/v1/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<MeResponse> me(Authentication auth) {
        String userId = ((CookieSessionUserDetails) auth.getPrincipal()).id();
        MeResponse response = userService.buildMeResponse(userId);
        Set<String> grades = response.user().roles().contains("teacher")
                ? teacherScopeAssignmentService.gradesOf(userId)
                : Set.of();
        return ApiResponse.of(response.withScopeGradeIds(grades));
    }

    @PatchMapping("/api/v1/me/profile")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<UserDto> updateProfile(@Valid @RequestBody UpdateProfileRequest request, Authentication auth) {
        String userId = ((CookieSessionUserDetails) auth.getPrincipal()).id();
        return ApiResponse.of(userService.updateProfile(userId, request));
    }
}
