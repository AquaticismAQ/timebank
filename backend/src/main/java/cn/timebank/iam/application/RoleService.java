package cn.timebank.iam.application;

import cn.timebank.iam.application.dto.PermissionDto;
import cn.timebank.iam.application.dto.RoleDto;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleRequest;

import java.util.List;

/** Contract §4.15: `/roles`, `/permissions`. */
public interface RoleService {

    List<PermissionDto> listPermissions();

    List<RoleDto> listRoles();

    RoleDto createRole(CreateRoleRequest request, String actorId);

    RoleDto updateRole(String id, UpdateRoleRequest request, String actorId);
}
