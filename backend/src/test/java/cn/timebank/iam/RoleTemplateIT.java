package cn.timebank.iam;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.RoleService;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleTemplateRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleTemplateRequest;
import cn.timebank.iam.application.dto.RoleTemplateDto;
import cn.timebank.iam.infrastructure.RoleRepository;
import cn.timebank.iam.web.RoleController;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 07-RoleFinetune.md §2.7: role template CRUD, the seeded 'Teacher'
 * template, and the guardrails keeping template rows strictly separate
 * from real roles despite sharing the {@code role} table.
 */
class RoleTemplateIT extends AbstractIntegrationTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private RoleController roleController;

    @Test
    void seededTeacherTemplateHasTheSameGrantsAsTheLiveTeacherRole() {
        RoleTemplateDto template = roleService.listRoleTemplates().stream()
                .filter(t -> t.name().equals("Teacher")).findFirst().orElseThrow();
        var teacherRole = roleService.listRoles().stream().filter(r -> r.code().equals("teacher"))
                .findFirst().orElseThrow();

        assertThat(template.permissionCodes()).isEqualTo(teacherRole.permissionCodes());
    }

    @Test
    void createRoleTemplateRejectsStudentOnlyPermissions() {
        CreateRoleTemplateRequest request = new CreateRoleTemplateRequest(
                "Reject Test", Set.of("eventProposals.create"));

        assertThatThrownBy(() -> roleService.createRoleTemplate(request, "usr_test_admin"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reserved for the student role");
    }

    @Test
    void createUpdateAndDeleteRoleTemplateRoundTrips() {
        RoleTemplateDto created = roleService.createRoleTemplate(
                new CreateRoleTemplateRequest("Custom Template", Set.of("serviceRecords.read")), "usr_test_admin");
        assertThat(created.permissionCodes()).containsExactly("serviceRecords.read");

        // Re-fetch rather than trust created.version() — same caution
        // UserScopeBondingIT.update() documents: not necessarily still
        // current by the time a later, separate request updates the row
        // (the @ManyToMany permissions association can bump the version on
        // its own flush).
        Long currentVersion = roleService.listRoleTemplates().stream()
                .filter(t -> t.id().equals(created.id())).findFirst().orElseThrow().version();
        RoleTemplateDto updated = roleService.updateRoleTemplate(created.id(),
                new UpdateRoleTemplateRequest("Renamed Template", Set.of("students.read", "students.manage"),
                        currentVersion),
                "usr_test_admin");
        assertThat(updated.name()).isEqualTo("Renamed Template");
        assertThat(updated.permissionCodes()).containsExactlyInAnyOrder("students.read", "students.manage");

        roleService.deleteRoleTemplate(created.id(), "usr_test_admin");
        assertThat(roleRepository.findById(created.id())).isEmpty();
    }

    @Test
    void listRolesNeverIncludesATemplateAndListRoleTemplatesNeverIncludesARole() {
        RoleTemplateDto template = roleService.createRoleTemplate(
                new CreateRoleTemplateRequest("Isolation Test", Set.of("serviceRecords.read")), "usr_test_admin");

        assertThat(roleService.listRoles()).noneMatch(r -> r.id().equals(template.id()));
        assertThat(roleService.listRoleTemplates()).noneMatch(t -> t.id().equals("role_teacher"));
    }

    @Test
    void aTemplatesCodeCanNeverBeAssignedToAUserAsItsRole() {
        RoleTemplateDto template = roleService.createRoleTemplate(
                new CreateRoleTemplateRequest("Unassignable Test", Set.of("serviceRecords.read")), "usr_test_admin");
        String templateCode = roleRepository.findById(template.id()).orElseThrow().getCode();

        CreateUserRequest request = new CreateUserRequest(
                "template_assign_test_" + OpaqueIds.generate("u"), "Template Assign Test",
                null, templateCode, "zh-CN");

        assertThatThrownBy(() -> userService.create(request, "usr_test_admin"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown role code");
    }

    @Test
    void roleEndpointsCannotReachATemplateRow() {
        RoleTemplateDto template = roleService.createRoleTemplate(
                new CreateRoleTemplateRequest("Cross Endpoint Test", Set.of("serviceRecords.read")), "usr_test_admin");

        assertThatThrownBy(() -> roleService.deleteRole(template.id(), "usr_test_admin"))
                .isInstanceOf(ApiException.class);
    }

    // ── Template management is admin/sysadmin-only, not just roles.manage-gated ──

    @Test
    void aCustomRoleHoldingRolesManageStillCannotCreateATemplate() {
        CreateRoleTemplateRequest request = new CreateRoleTemplateRequest(
                "Blocked Create", Set.of("serviceRecords.read"));

        assertThatThrownBy(() -> asPrincipal(nonAdminManagerAuth(),
                () -> roleController.createRoleTemplate(request, nonAdminManagerAuth())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("admin/sysadmin");
    }

    @Test
    void aCustomRoleHoldingRolesManageStillCannotUpdateOrDeleteATemplate() {
        RoleTemplateDto template = roleService.createRoleTemplate(
                new CreateRoleTemplateRequest("Blocked Write Test", Set.of("serviceRecords.read")), "usr_test_admin");
        UpdateRoleTemplateRequest update = new UpdateRoleTemplateRequest(
                "Renamed", Set.of("serviceRecords.read"), template.version());

        assertThatThrownBy(() -> asPrincipal(nonAdminManagerAuth(),
                () -> roleController.updateRoleTemplate(template.id(), update, nonAdminManagerAuth())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("admin/sysadmin");
        assertThatThrownBy(() -> asPrincipal(nonAdminManagerAuth(),
                () -> roleController.deleteRoleTemplate(template.id(), nonAdminManagerAuth())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("admin/sysadmin");
    }

    @Test
    void adminCanCreateATemplateThroughTheController() {
        CreateRoleTemplateRequest request = new CreateRoleTemplateRequest(
                "Allowed Create", Set.of("serviceRecords.read"));

        var created = asPrincipal(adminAuth(), () -> roleController.createRoleTemplate(request, adminAuth()));

        assertThat(created.data().name()).isEqualTo("Allowed Create");
    }

    private <T> T asPrincipal(Authentication auth, Supplier<T> action) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private Authentication adminAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of("roles.read", "roles.manage"), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    /** A hypothetical custom role granted `roles.manage` but not admin/sysadmin — proves the code-based restriction, not just the permission gate. */
    private Authentication nonAdminManagerAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_nonadmin_manager", "usr_test_nonadmin_manager", "hash", "Non-Admin Manager", true, false,
                "zh-CN", Set.of("teacher"), Set.of("roles.read", "roles.manage"), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
