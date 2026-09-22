package cn.timebank.iam.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.MeResponse;
import cn.timebank.iam.application.dto.UpdateProfileRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.SessionAuthorityRefresher;
import cn.timebank.security.application.TeacherScopeAssignmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    private final SessionAuthorityRefresher sessionAuthorityRefresher;

    public MeController(UserService userService, TeacherScopeAssignmentService teacherScopeAssignmentService,
                        SessionAuthorityRefresher sessionAuthorityRefresher) {
        this.userService = userService;
        this.teacherScopeAssignmentService = teacherScopeAssignmentService;
        this.sessionAuthorityRefresher = sessionAuthorityRefresher;
    }

    /**
     * Also re-seats the session's authorities from the database (issue 157 §6).
     *
     * <p>They are a snapshot taken at login — serialized onto the session row —
     * so a permission granted or revoked afterwards did not reach a live session
     * for up to eight hours. The client re-reads this endpoint on every app load
     * for exactly the same reason, and doing both halves here is what keeps the
     * screen and the server from disagreeing: the alternative was a browser that
     * had learned about a new permission talking to a session that had not,
     * which showed as an empty management screen rather than as a refusal.</p>
     */
    @GetMapping("/api/v1/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<MeResponse> me(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        sessionAuthorityRefresher.refresh(auth, request, response);
        String userId = ((CookieSessionUserDetails) auth.getPrincipal()).id();
        MeResponse me = userService.buildMeResponse(userId);
        Set<String> grades = me.user().roles().contains("teacher")
                ? teacherScopeAssignmentService.gradesOf(userId)
                : Set.of();
        return ApiResponse.of(me.withScopeGradeIds(grades));
    }

    @PatchMapping("/api/v1/me/profile")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<UserDto> updateProfile(@Valid @RequestBody UpdateProfileRequest request, Authentication auth) {
        String userId = ((CookieSessionUserDetails) auth.getPrincipal()).id();
        return ApiResponse.of(userService.updateProfile(userId, request));
    }
}
