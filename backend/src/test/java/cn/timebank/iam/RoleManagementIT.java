package cn.timebank.iam;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.RoleService;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.RoleDto;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleRequest;
import cn.timebank.iam.infrastructure.RoleRepository;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 07-RoleFinetune.md §2.2-§2.4: role protection, the student-submission
 * permission ceiling, and delete semantics — proven against real MySQL,
 * not just unit-level.
 */
class RoleManagementIT extends AbstractIntegrationTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserService userService;

    @Test
    void seededStudentAdminSysadminAreProtectedAndTeacherIsNot() {
        assertThat(roleRepository.findByCode("student").orElseThrow().isProtected()).isTrue();
        assertThat(roleRepository.findByCode("admin").orElseThrow().isProtected()).isTrue();
        assertThat(roleRepository.findByCode("sysadmin").orElseThrow().isProtected()).isTrue();
        assertThat(roleRepository.findByCode("teacher").orElseThrow().isProtected()).isFalse();
    }

    @Test
    void adminAndSysadminHoldIdenticalPermissionSets() {
        RoleDto admin = roleService.listRoles().stream().filter(r -> r.code().equals("admin")).findFirst().orElseThrow();
        RoleDto sysadmin = roleService.listRoles().stream().filter(r -> r.code().equals("sysadmin")).findFirst().orElseThrow();

        assertThat(admin.permissionCodes()).isEqualTo(sysadmin.permissionCodes());
    }

    @Test
    void createRoleRejectsEachStudentSubmissionPermission() {
        for (String forbidden : Set.of("eventProposals.create", "applications.create", "serviceRecords.create")) {
            CreateRoleRequest request = new CreateRoleRequest(
                    "reject_" + OpaqueIds.generate("r").substring(0, 10), "Should Be Rejected", 480, Set.of(forbidden));
            assertThatThrownBy(() -> roleService.createRole(request, "usr_test_admin"))
                    .as("permission=" + forbidden)
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("reserved for the student role");
        }
    }

    @Test
    void updateRoleRejectsStudentSubmissionPermissionsTooNotJustCreate() {
        RoleDto created = roleService.createRole(new CreateRoleRequest(
                "upd_reject_" + OpaqueIds.generate("r").substring(0, 10), "Update Reject Test", 480,
                Set.of("serviceRecords.read")), "usr_test_admin");
        // Re-fetch rather than trust the create-time version — same caution
        // UserScopeBondingIT.update() documents: not necessarily still
        // current by the time a later, separate request updates the row.
        RoleDto role = roleService.listRoles().stream().filter(r -> r.id().equals(created.id())).findFirst().orElseThrow();

        UpdateRoleRequest update = new UpdateRoleRequest(null, null, Set.of("applications.create"), role.version());

        assertThatThrownBy(() -> roleService.updateRole(role.id(), update, "usr_test_admin"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reserved for the student role");
    }

    @Test
    void createRoleAllowsAnyNonStudentSubmissionPermissionIncludingSysadminExclusiveOnes() {
        RoleDto role = roleService.createRole(new CreateRoleRequest(
                "allowed_" + OpaqueIds.generate("r").substring(0, 10), "Allowed Permissions Test", 480,
                Set.of("serviceRecords.read", "roles.manage", "lifecycle.purge")), "usr_test_admin");

        assertThat(role.permissionCodes()).containsExactlyInAnyOrder("serviceRecords.read", "roles.manage", "lifecycle.purge");
    }

    @Test
    void deletingAProtectedRoleIsBlockedRegardlessOfAssignment() {
        RoleDto student = roleService.listRoles().stream().filter(r -> r.code().equals("student")).findFirst().orElseThrow();

        assertThatThrownBy(() -> roleService.deleteRole(student.id(), "usr_test_admin"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("protected");
    }

    @Test
    void updatingAProtectedRoleIsBlockedRegardlessOfWhatChanges() {
        // 07-RoleFinetune.md §2.6.1: protected roles are now fully locked,
        // not just undeletable — even an otherwise-valid rename is rejected.
        RoleDto admin = roleService.listRoles().stream().filter(r -> r.code().equals("admin")).findFirst().orElseThrow();

        UpdateRoleRequest renameAttempt = new UpdateRoleRequest("Renamed Admin", null, null, admin.version());

        assertThatThrownBy(() -> roleService.updateRole(admin.id(), renameAttempt, "usr_test_admin"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("protected");

        assertThat(roleRepository.findById(admin.id()).orElseThrow().getName()).isEqualTo(admin.name());
    }

    @Test
    void deletingAnUnprotectedRoleStillAssignedToAUserIsBlocked() {
        RoleDto role = roleService.createRole(new CreateRoleRequest(
                "assigned_" + OpaqueIds.generate("r").substring(0, 10), "Assigned Role Test", 480,
                Set.of("serviceRecords.read")), "usr_test_admin");
        userService.create(new CreateUserRequest(
                "role_del_test_" + OpaqueIds.generate("u"), "InitialPass123!", "Role Delete Test",
                null, role.code(), "zh-CN"), "usr_test_admin");

        assertThatThrownBy(() -> roleService.deleteRole(role.id(), "usr_test_admin"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("user");

        // 07-RoleFinetune.md §2.9: RoleDto.userCount() is what the frontend
        // uses to disable the Delete action up front — must reflect this
        // same assignment before the frontend can rely on it.
        RoleDto refetched = roleService.listRoles().stream().filter(r -> r.id().equals(role.id())).findFirst().orElseThrow();
        assertThat(refetched.userCount()).isEqualTo(1);
    }

    @Test
    void deletingAnUnassignedUnprotectedRoleSucceeds() {
        RoleDto role = roleService.createRole(new CreateRoleRequest(
                "deletable_" + OpaqueIds.generate("r").substring(0, 10), "Deletable Role Test", 480,
                Set.of("serviceRecords.read")), "usr_test_admin");
        assertThat(role.userCount()).isEqualTo(0);

        roleService.deleteRole(role.id(), "usr_test_admin");

        assertThat(roleRepository.findById(role.id())).isEmpty();
    }

    @Test
    void everyRealUserHoldsExactlyOneRole() {
        var created = userService.create(new CreateUserRequest(
                "cardinality_" + OpaqueIds.generate("u"), "InitialPass123!", "Cardinality Test",
                null, "teacher", "zh-CN"), "usr_test_admin");

        // roleCode is a single field on the wire (07-RoleFinetune.md §2.5) —
        // there is no API shape that could express a second role here at all.
        assertThat(created.roleCode()).isEqualTo("teacher");
    }
}
