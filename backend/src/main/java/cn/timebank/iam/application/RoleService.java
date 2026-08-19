package cn.timebank.iam.application;

import cn.timebank.iam.application.dto.PermissionDto;
import cn.timebank.iam.application.dto.RoleDto;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleRequest;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleTemplateRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleTemplateRequest;
import cn.timebank.iam.application.dto.RoleTemplateDto;

import java.util.List;

/** Contract §4.15: `/roles`, `/permissions`, `/role-templates`. */
public interface RoleService {

    List<PermissionDto> listPermissions();

    List<RoleDto> listRoles();

    RoleDto createRole(CreateRoleRequest request, String actorId);

    RoleDto updateRole(String id, UpdateRoleRequest request, String actorId);

    /** 07-RoleFinetune.md §2.2/§2.4: 409 if the role is protected, or if any user currently holds it. */
    void deleteRole(String id, String actorId);

    /** 07-RoleFinetune.md §2.7: role templates — see {@code RoleTemplateDto}. */
    List<RoleTemplateDto> listRoleTemplates();

    RoleTemplateDto createRoleTemplate(CreateRoleTemplateRequest request, String actorId);

    RoleTemplateDto updateRoleTemplate(String id, UpdateRoleTemplateRequest request, String actorId);

    /** Never assigned to a user, so unlike {@link #deleteRole}, this never 409s on assignment. */
    void deleteRoleTemplate(String id, String actorId);
}
