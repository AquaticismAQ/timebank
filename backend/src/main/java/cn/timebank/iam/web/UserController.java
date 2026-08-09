package cn.timebank.iam.web;

import cn.timebank.common.error.ApiException;
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
import cn.timebank.security.application.TeacherScopeAssignmentService;
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

/**
 * Contract §4.15: `/users`. Also where {@code iam} and {@code security}'s
 * application layers are allowed to meet — see {@code UserServiceImpl}'s
 * class Javadoc and {@code UserDto.withScopeGradeIds} — so grade-bond
 * reads/writes (issue 002) happen here, not in {@code UserServiceImpl}.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Set<String> SORTABLE = Set.of("id", "username", "displayName", "createdAt");
    private static final String TEACHER_ROLE = "teacher";
    private static final String ADMIN_ROLE = "admin";
    private static final String STUDENT_ROLE = "student";

    private final UserService userService;
    private final SessionRevocationService sessionRevocationService;
    private final TeacherScopeAssignmentService teacherScopeAssignmentService;

    public UserController(UserService userService, SessionRevocationService sessionRevocationService,
                           TeacherScopeAssignmentService teacherScopeAssignmentService) {
        this.userService = userService;
        this.sessionRevocationService = sessionRevocationService;
        this.teacherScopeAssignmentService = teacherScopeAssignmentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('users.read')")
    public ApiResponse<List<UserDto>> list(PageQuery query) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<UserDto> page = userService.list(pageable).map(this::withScopeGradeIds);
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
        requireRoleFields(request.roleCodes(), request.scopeGradeIds(), request.studentId(), request.staffId());
        String actorId = principalId(auth);
        UserDto created = userService.create(request, actorId);
        if (created.roleCodes().contains(TEACHER_ROLE)) {
            teacherScopeAssignmentService.replaceGradeScope(created.id(), request.scopeGradeIds());
        }
        return ApiResponse.of(withScopeGradeIds(created));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('users.manage')")
    public ApiResponse<UserDto> update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request,
                                        Authentication auth) {
        UserDto before = withScopeGradeIds(userService.getById(id));
        Set<String> effectiveRoles = request.roleCodes() != null ? request.roleCodes() : before.roleCodes();
        Set<String> effectiveGrades = request.scopeGradeIds() != null ? request.scopeGradeIds() : before.scopeGradeIds();
        String effectiveStudentId = request.studentId() != null ? request.studentId() : before.studentId();
        String effectiveStaffId = request.staffId() != null ? request.staffId() : before.staffId();
        requireRoleFields(effectiveRoles, effectiveGrades, effectiveStudentId, effectiveStaffId);

        UserDto updated = userService.update(id, request, principalId(auth));
        if ("INACTIVE".equals(request.status()) && !"INACTIVE".equals(before.status())) {
            sessionRevocationService.revokeAllSessionsForUser(updated.username());
        }
        if (updated.roleCodes().contains(TEACHER_ROLE)) {
            teacherScopeAssignmentService.replaceGradeScope(id, effectiveGrades);
        } else {
            // Role changed away from teacher (or was never one) — clear any stale bond.
            teacherScopeAssignmentService.replaceGradeScope(id, Set.of());
        }
        return ApiResponse.of(withScopeGradeIds(updated));
    }

    /**
     * Issue 003's required-ness rules — deliberately checked here, not in
     * {@code CreateUserRequest}'s bean validation or {@code UserServiceImpl}:
     * each rule is conditional on {@code roleCodes}, which plain
     * per-field annotations can't express, and enforcing it in the service
     * would also apply to the many existing IT tests that call
     * {@code UserService} directly as a fixture-setup shortcut without
     * supplying every field a real UI submission would (see
     * {@code CreateUserRequest}'s Javadoc). Real traffic always goes
     * through this controller, so checking here is sufficient.
     */
    private void requireRoleFields(Set<String> roleCodes, Set<String> scopeGradeIds, String studentId, String staffId) {
        if (roleCodes.contains(TEACHER_ROLE) && (scopeGradeIds == null || scopeGradeIds.isEmpty())) {
            throw ApiException.invalidRequest("At least one grade is required for a teacher account.");
        }
        if (roleCodes.contains(STUDENT_ROLE) && (studentId == null || studentId.isBlank())) {
            throw ApiException.invalidRequest("A linked student is required for a student account.");
        }
        if ((roleCodes.contains(TEACHER_ROLE) || roleCodes.contains(ADMIN_ROLE)) && (staffId == null || staffId.isBlank())) {
            throw ApiException.invalidRequest("A staff ID is required for teacher and admin accounts.");
        }
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

    private UserDto withScopeGradeIds(UserDto dto) {
        Set<String> grades = dto.roleCodes().contains(TEACHER_ROLE)
                ? teacherScopeAssignmentService.gradesOf(dto.id())
                : Set.of();
        return dto.withScopeGradeIds(grades);
    }
}
