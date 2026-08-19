package cn.timebank.iam.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.iam.application.RoleService;
import cn.timebank.iam.application.dto.PermissionDto;
import cn.timebank.iam.application.dto.RoleDto;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleRequest;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleTemplateRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleTemplateRequest;
import cn.timebank.iam.application.dto.RoleTemplateDto;
import cn.timebank.common.error.ApiException;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.TimeBankPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Contract §4.15: `/roles`, `/permissions`. */
@RestController
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/api/v1/permissions")
    @PreAuthorize("hasAuthority('roles.read')")
    public ApiResponse<List<PermissionDto>> listPermissions() {
        return ApiResponse.of(roleService.listPermissions());
    }

    @GetMapping("/api/v1/roles")
    @PreAuthorize("hasAuthority('roles.read')")
    public ApiResponse<List<RoleDto>> listRoles() {
        return ApiResponse.of(roleService.listRoles());
    }

    @PostMapping("/api/v1/roles")
    @PreAuthorize("hasAuthority('roles.manage')")
    public ApiResponse<RoleDto> createRole(@Valid @RequestBody CreateRoleRequest request, Authentication auth) {
        return ApiResponse.of(roleService.createRole(request, principalId(auth)));
    }

    @PatchMapping("/api/v1/roles/{id}")
    @PreAuthorize("hasAuthority('roles.manage')")
    public ApiResponse<RoleDto> updateRole(@PathVariable String id, @Valid @RequestBody UpdateRoleRequest request,
                                            Authentication auth) {
        return ApiResponse.of(roleService.updateRole(id, request, principalId(auth)));
    }

    /** 07-RoleFinetune.md §2.2/§2.4: 409 if protected or still assigned to any user. */
    @DeleteMapping("/api/v1/roles/{id}")
    @PreAuthorize("hasAuthority('roles.manage')")
    public ApiResponse<Void> deleteRole(@PathVariable String id, Authentication auth) {
        roleService.deleteRole(id, principalId(auth));
        return ApiResponse.of(null);
    }

    /**
     * 07-RoleFinetune.md §2.7: reads stay {@code roles.read}-gated like
     * everything else here, so any {@code roles.manage} holder creating a
     * role can still use a template as a preset. Writes (create/update/
     * delete below) are additionally restricted to {@code admin}/
     * {@code sysadmin} by role code, not just permission — curating the
     * template library itself is narrower than ordinary role management,
     * so a hypothetical future custom role holding {@code roles.manage}
     * still couldn't touch the template set.
     */
    @GetMapping("/api/v1/role-templates")
    @PreAuthorize("hasAuthority('roles.read')")
    public ApiResponse<List<RoleTemplateDto>> listRoleTemplates() {
        return ApiResponse.of(roleService.listRoleTemplates());
    }

    @PostMapping("/api/v1/role-templates")
    @PreAuthorize("hasAuthority('roles.manage')")
    public ApiResponse<RoleTemplateDto> createRoleTemplate(@Valid @RequestBody CreateRoleTemplateRequest request,
                                                             Authentication auth) {
        requireTemplateManager(auth);
        return ApiResponse.of(roleService.createRoleTemplate(request, principalId(auth)));
    }

    @PatchMapping("/api/v1/role-templates/{id}")
    @PreAuthorize("hasAuthority('roles.manage')")
    public ApiResponse<RoleTemplateDto> updateRoleTemplate(@PathVariable String id,
                                                             @Valid @RequestBody UpdateRoleTemplateRequest request,
                                                             Authentication auth) {
        requireTemplateManager(auth);
        return ApiResponse.of(roleService.updateRoleTemplate(id, request, principalId(auth)));
    }

    @DeleteMapping("/api/v1/role-templates/{id}")
    @PreAuthorize("hasAuthority('roles.manage')")
    public ApiResponse<Void> deleteRoleTemplate(@PathVariable String id, Authentication auth) {
        requireTemplateManager(auth);
        roleService.deleteRoleTemplate(id, principalId(auth));
        return ApiResponse.of(null);
    }

    private static final Set<String> TEMPLATE_MANAGER_ROLES = Set.of("admin", "sysadmin");

    /** 07-RoleFinetune.md §2.7: code-based, not permission-based — see the Javadoc above {@code listRoleTemplates}. */
    private void requireTemplateManager(Authentication auth) {
        Set<String> roleCodes = ((TimeBankPrincipal) auth.getPrincipal()).roleCodes();
        if (Collections.disjoint(roleCodes, TEMPLATE_MANAGER_ROLES)) {
            throw ApiException.forbidden("Only admin/sysadmin may manage role templates.");
        }
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
