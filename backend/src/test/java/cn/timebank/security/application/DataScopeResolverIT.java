package cn.timebank.security.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.iam.application.RoleService;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleRequest;
import cn.timebank.security.domain.TeacherScopeAssignment;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1.7's exit criteria: the resolver produces the right {@link
 * ScopeDescriptor} from real assignment rows in real MySQL — SELF for a
 * principal with none, GRADES/CLASSES/PROJECTS from what is actually
 * assigned, ALL only when the caller explicitly grants it. No business
 * resource exists yet to wire this into (B2/B4); this proves the
 * mechanism in isolation, as documented in `Docs/implementation/00-Status.md`.
 */
class DataScopeResolverIT extends AbstractIntegrationTest {

    @Autowired
    private DataScopeResolver dataScopeResolver;

    @Autowired
    private TeacherScopeAssignmentRepository scopeAssignmentRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private RoleService roleService;

    /**
     * 07-RoleFinetune.md §2.1: {@code hasFullDataScope()} — the replacement
     * for the ~11 hardcoded {@code contains("admin") || contains("sysadmin")}
     * checks — must be true for any non-{@code student} role, a brand-new
     * custom role included, and false only for {@code student}.
     */
    @Test
    void hasFullDataScopeIsTrueForEveryNonStudentRoleIncludingABrandNewCustomOne() {
        TimeBankPrincipal teacher = principal("hfds_teacher", "teacher");
        TimeBankPrincipal admin = principal("hfds_admin", "admin");
        String customRoleCode = "hfds_custom_" + OpaqueIds.generate("r").substring(0, 10);
        roleService.createRole(new CreateRoleRequest(customRoleCode, "HFDS Custom Role", 480,
                Set.of("serviceRecords.read")), "usr_test_admin");
        TimeBankPrincipal custom = principal("hfds_custom_user", customRoleCode);

        assertThat(teacher.hasFullDataScope()).isTrue();
        assertThat(admin.hasFullDataScope()).isTrue();
        assertThat(custom.hasFullDataScope()).isTrue();
    }

    @Test
    void hasFullDataScopeIsFalseForTheStudentRole() {
        TimeBankPrincipal student = principal("hfds_student", "student");

        assertThat(student.hasFullDataScope()).isFalse();
    }

    @Test
    void grantAllShortCircuitsRegardlessOfAssignments() {
        TimeBankPrincipal principal = principal("scope_all");

        ScopeDescriptor result = dataScopeResolver.resolve(principal, "serviceRecords", true);

        assertThat(result).isInstanceOf(ScopeDescriptor.All.class);
    }

    @Test
    void noAssignmentsResolvesToSelf() {
        TimeBankPrincipal principal = principal("scope_self");

        ScopeDescriptor result = dataScopeResolver.resolve(principal, "serviceRecords", false);

        assertThat(result).isInstanceOf(ScopeDescriptor.Self.class);
    }

    @Test
    void gradeAssignmentsResolveToGrades() {
        TimeBankPrincipal principal = principal("scope_grades");
        assignScope(principal.id(), "serviceRecords", TeacherScopeAssignment.ScopeType.GRADE, "grade_9");
        assignScope(principal.id(), "serviceRecords", TeacherScopeAssignment.ScopeType.GRADE, "grade_10");

        ScopeDescriptor result = dataScopeResolver.resolve(principal, "serviceRecords", false);

        assertThat(result).isInstanceOf(ScopeDescriptor.Grades.class);
        assertThat(((ScopeDescriptor.Grades) result).gradeIds()).containsExactlyInAnyOrder("grade_9", "grade_10");
    }

    @Test
    void assignmentsForADifferentResourceTypeDoNotLeakIn() {
        TimeBankPrincipal principal = principal("scope_isolated");
        assignScope(principal.id(), "projects", TeacherScopeAssignment.ScopeType.PROJECT, "proj_1");

        // No assignment for "serviceRecords" specifically — must fall back to Self,
        // not accidentally see the "projects" assignment.
        ScopeDescriptor result = dataScopeResolver.resolve(principal, "serviceRecords", false);

        assertThat(result).isInstanceOf(ScopeDescriptor.Self.class);
    }

    private TimeBankPrincipal principal(String usernamePrefix) {
        return principal(usernamePrefix, "teacher");
    }

    private TimeBankPrincipal principal(String usernamePrefix, String roleCode) {
        var user = userService.create(new CreateUserRequest(
                usernamePrefix + "_" + OpaqueIds.generate("u"), "Scope Test",
                usernamePrefix + "@example.com", roleCode, "zh-CN"), "system");
        return new TimeBankPrincipal() {
            public String id() { return user.id(); }
            public String displayName() { return user.displayName(); }
            public Set<String> roleCodes() { return Set.of(user.roleCode()); }
            public Set<String> permissionCodes() { return Set.of(); }
            public String preferredLanguage() { return "zh-CN"; }
            public String studentId() { return null; }
        };
    }

    private void assignScope(String userId, String resourceType, TeacherScopeAssignment.ScopeType scopeType, String value) {
        scopeAssignmentRepository.save(new TeacherScopeAssignment(
                OpaqueIds.generate("scope"), userId, resourceType, scopeType, value, Instant.now()));
    }
}
