package cn.timebank.iam;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UpdateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.web.UserController;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.TeacherScopeAssignmentService;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue 002: creating/editing a teacher account writes/replaces its grade
 * bond, across every resource type {@code DataScopeResolver} understands —
 * proven end to end at the controller layer, since that's where the
 * iam/security boundary forces this orchestration to live (see
 * {@code UserController}'s class Javadoc).
 */
class UserScopeBondingIT extends AbstractIntegrationTest {

    private static final List<String> MANAGED_RESOURCE_TYPES =
            List.of("serviceRecords", "eventProposals", "students", "accounts", "cases");

    @Autowired
    private UserController userController;

    @Autowired
    private UserService userService;

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

        UserDto updated = update(created, null, Set.of("admin"));

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
    void listEnrichesEachRowWithItsGradeBond() {
        UserDto created = create("teacher", Set.of("grade_10"));

        Authentication auth = adminAuth();
        ApiResponse<List<UserDto>> page = asAdmin(auth,
                () -> userController.list(new PageQuery(1, 100, List.of(), Map.of(), null)));

        assertThat(page.data()).filteredOn(u -> u.id().equals(created.id()))
                .extracting(UserDto::scopeGradeIds)
                .containsExactly(Set.of("grade_10"));
    }

    private UserDto create(String roleCode, Set<String> scopeGradeIds) {
        String username = "scope_bonding_" + OpaqueIds.generate("u");
        CreateUserRequest request = new CreateUserRequest(username, "InitialPass123!", "Scope Bonding Test", null,
                Set.of(roleCode), "zh-CN", scopeGradeIds);
        Authentication auth = adminAuth();
        return asAdmin(auth, () -> userController.create(request, auth).data());
    }

    private UserDto update(UserDto current, Set<String> scopeGradeIds, Set<String> roleCodes) {
        // Re-fetch rather than trust current.version() — the version at
        // creation time isn't necessarily still current by the time a
        // later, separate request updates the row (same reason a real
        // edit form re-fetches before submitting rather than reusing a
        // stale value from whenever the row was first loaded).
        Long latestVersion = userService.getById(current.id()).version();
        UpdateUserRequest request = new UpdateUserRequest(null, null, null, roleCodes, latestVersion, scopeGradeIds);
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
