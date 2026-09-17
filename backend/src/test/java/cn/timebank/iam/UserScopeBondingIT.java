package cn.timebank.iam;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.iam.application.RoleService;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleRequest;
import cn.timebank.iam.application.dto.UpdateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.web.UserController;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.TeacherScopeAssignmentService;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue 002: creating/editing a teacher account writes/replaces its grade
 * bond, across every resource type {@code DataScopeResolver} understands —
 * proven end to end at the controller layer, since that's where the
 * iam/security boundary forces this orchestration to live (see
 * {@code UserController}'s class Javadoc). Also issue 003: the required-ness
 * and uniqueness rules for studentId/staffId/scopeGradeIds, which live at
 * the same controller layer for the same reason.
 */
class UserScopeBondingIT extends AbstractIntegrationTest {

    private static final List<String> MANAGED_RESOURCE_TYPES =
            List.of("serviceRecords", "eventProposals", "students", "accounts", "cases");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private UserController userController;

    @Autowired
    private UserService userService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private StudentService studentService;

    @Autowired
    private TeacherScopeAssignmentService teacherScopeAssignmentService;

    @Autowired
    private TeacherScopeAssignmentRepository scopeAssignmentRepository;

    @Test
    void creatingATeacherWritesGradeScopeAcrossEveryManagedResourceType() {
        UserDto created = create("teacher", Set.of("grade_7"));

        assertThat(created.scopeGradeIds()).containsExactly("grade_7");
        assertThat(teacherScopeAssignmentService.gradesOf(created.id())).containsExactly("grade_7");
        for (String resourceType : MANAGED_RESOURCE_TYPES) {
            assertThat(scopeAssignmentRepository.findByUserIdAndResourceType(created.id(), resourceType))
                    .as("resourceType=" + resourceType)
                    .extracting(a -> a.getScopeValue())
                    .containsExactly("grade_7");
        }
    }

    @Test
    void updatingGradesReplacesRatherThanAdds() {
        UserDto created = create("teacher", Set.of("grade_7"));

        UserDto updated = update(created, Set.of("grade_8", "grade_9"), null);

        assertThat(updated.scopeGradeIds()).containsExactlyInAnyOrder("grade_8", "grade_9");
        assertThat(teacherScopeAssignmentService.gradesOf(created.id())).containsExactlyInAnyOrder("grade_8", "grade_9");
    }

    @Test
    void removingTeacherRoleClearsTheBond() {
        UserDto created = create("teacher", Set.of("grade_7"));
        assertThat(teacherScopeAssignmentService.gradesOf(created.id())).isNotEmpty();

        UserDto updated = update(created, null, "admin");

        assertThat(updated.scopeGradeIds()).isEmpty();
        assertThat(teacherScopeAssignmentService.gradesOf(created.id())).isEmpty();
    }

    @Test
    void nonTeacherRoleIgnoresProvidedScopeGradeIds() {
        UserDto created = create("admin", Set.of("grade_6"));

        assertThat(created.scopeGradeIds()).isEmpty();
        assertThat(teacherScopeAssignmentService.gradesOf(created.id())).isEmpty();
    }

    @Test
    void aCustomNonTeacherStaffRoleAlsoRequiresAndWritesGradeScope() {
        // 07-RoleFinetune.md §2.6.2: grade assignment is a structural rule —
        // any role other than admin/student/sysadmin — not a literal
        // "teacher" special case. Proven here with a role that is neither.
        String code = "custom_staff_" + OpaqueIds.generate("r").substring(0, 8);
        roleService.createRole(new CreateRoleRequest(code, "Custom Staff", 480, Set.of("serviceRecords.read")),
                "usr_test_admin");

        assertThatThrownBy(() -> create(code, Set.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("grade");

        UserDto created = create(code, Set.of("grade_7"));
        assertThat(created.scopeGradeIds()).containsExactly("grade_7");
        assertThat(teacherScopeAssignmentService.gradesOf(created.id())).containsExactly("grade_7");
    }

    @Test
    void listEnrichesEachRowWithItsGradeBond() {
        UserDto created = create("teacher", Set.of("grade_10"));

        Authentication auth = adminAuth();
        ApiResponse<List<UserDto>> page = asAdmin(auth,
                () -> userController.list(new PageQuery(1, 100, List.of(), Map.of(), null)));

        assertThat(page.data()).filteredOn(u -> u.id().equals(created.id()))
                .extracting(UserDto::scopeGradeIds)
                .containsExactly(Set.of("grade_10"));
    }

    // ── Issue 003: required-ness and uniqueness ─────────────────────────

    @Test
    void teacherWithoutAnyGradeIsRejected() {
        assertThatThrownBy(() -> create("teacher", Set.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("grade");
    }

    @Test
    void teacherWithoutStaffIdIsRejected() {
        Authentication auth = adminAuth();
        CreateUserRequest request = new CreateUserRequest("scope_bonding_" + OpaqueIds.generate("u"),
                "No Staff Id", null, "teacher", "zh-CN", Set.of("grade_7"), null, null);
        assertThatThrownBy(() -> asAdmin(auth, () -> userController.create(request, auth)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("staff");
    }

    @Test
    void duplicateStaffIdIsRejected() {
        String staffId = "staff_" + OpaqueIds.generate("s");
        createWithStaffId("teacher", Set.of("grade_7"), staffId);

        Authentication auth = adminAuth();
        CreateUserRequest request = new CreateUserRequest("scope_bonding_" + OpaqueIds.generate("u"),
                "Duplicate Staff Id", null, "teacher", "zh-CN", Set.of("grade_8"), null, staffId);
        assertThatThrownBy(() -> asAdmin(auth, () -> userController.create(request, auth)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void studentRoleRequiresALinkedStudent() {
        Authentication auth = adminAuth();
        CreateUserRequest request = new CreateUserRequest("scope_bonding_" + OpaqueIds.generate("u"),
                "No Linked Student", null, "student", "zh-CN", Set.of(), null, null);
        assertThatThrownBy(() -> asAdmin(auth, () -> userController.create(request, auth)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("student");
    }

    @Test
    void studentRoleRejectsAnUnknownStudentId() {
        Authentication auth = adminAuth();
        CreateUserRequest request = new CreateUserRequest("scope_bonding_" + OpaqueIds.generate("u"),
                "Bad Link", null, "student", "zh-CN", Set.of(), "std_does_not_exist", null);
        assertThatThrownBy(() -> asAdmin(auth, () -> userController.create(request, auth)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void studentRoleLinksToARealStudentAndRejectsRelinkingTheSameOne() {
        StudentDetailDto student = createRosterStudent();
        Authentication auth = adminAuth();

        CreateUserRequest first = new CreateUserRequest("scope_bonding_" + OpaqueIds.generate("u"),
                "Linked Student", null, "student", "zh-CN", Set.of(), student.id(), null);
        UserDto created = asAdmin(auth, () -> userController.create(first, auth)).data();
        assertThat(created.studentId()).isEqualTo(student.id());

        CreateUserRequest second = new CreateUserRequest("scope_bonding_" + OpaqueIds.generate("u"),
                "Same Student Again", null, "student", "zh-CN", Set.of(), student.id(), null);
        assertThatThrownBy(() -> asAdmin(auth, () -> userController.create(second, auth)))
                .isInstanceOf(ApiException.class);
    }

    private UserDto create(String roleCode, Set<String> scopeGradeIds) {
        return createWithStaffId(roleCode, scopeGradeIds, "staff_" + OpaqueIds.generate("s"));
    }

    private UserDto createWithStaffId(String roleCode, Set<String> scopeGradeIds, String staffId) {
        String username = "scope_bonding_" + OpaqueIds.generate("u");
        CreateUserRequest request = new CreateUserRequest(username, "Scope Bonding Test", null,
                roleCode, "zh-CN", scopeGradeIds, null, staffId);
        Authentication auth = adminAuth();
        return asAdmin(auth, () -> userController.create(request, auth).data());
    }

    private StudentDetailDto createRosterStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_scope_bonding_" + n, "Roster Student " + n, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1));
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private UserDto update(UserDto current, Set<String> scopeGradeIds, String roleCode) {
        // Re-fetch rather than trust current.version() — the version at
        // creation time isn't necessarily still current by the time a
        // later, separate request updates the row (same reason a real
        // edit form re-fetches before submitting rather than reusing a
        // stale value from whenever the row was first loaded).
        Long latestVersion = userService.getById(current.id()).version();
        UpdateUserRequest request = new UpdateUserRequest(null, null, null, roleCode, latestVersion, scopeGradeIds);
        Authentication auth = adminAuth();
        return asAdmin(auth, () -> userController.update(current.id(), request, auth).data());
    }

    /**
     * {@code @PreAuthorize} reads {@code SecurityContextHolder}, not the
     * {@code Authentication} parameter a controller method happens to take
     * — that parameter is only ever populated from an already-established
     * context when Spring MVC resolves it for a real HTTP request. Calling
     * the controller bean directly (as every other cross-module-boundary
     * proof in this suite does) still goes through the method-security AOP
     * proxy, so the context has to be set by hand here.
     */
    private <T> T asAdmin(Authentication auth, java.util.function.Supplier<T> action) {
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return action.get();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private Authentication adminAuth() {
        // Spring Security authorities come from permissionCodes(), not roleCodes()
        // (CookieSessionUserDetails.getAuthorities) — @PreAuthorize on
        // UserController needs these explicitly, unlike StudentServiceImpl-level
        // tests elsewhere in this suite that call an unguarded service directly.
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of("users.manage", "users.read"), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
