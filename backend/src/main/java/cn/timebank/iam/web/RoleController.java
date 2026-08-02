package cn.timebank.iam.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.iam.application.RoleService;
import cn.timebank.iam.application.dto.PermissionDto;
import cn.timebank.iam.application.dto.RoleDto;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
