package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.dto.PermissionDto;
import cn.timebank.iam.application.dto.RoleDto;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleRequest;
import cn.timebank.iam.domain.Permission;
import cn.timebank.iam.domain.Role;
import cn.timebank.iam.infrastructure.PermissionRepository;
import cn.timebank.iam.infrastructure.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final AuditService auditService;

    RoleServiceImpl(RoleRepository roleRepository, PermissionRepository permissionRepository, AuditService auditService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionDto> listPermissions() {
        return permissionRepository.findAll().stream()
                .map(p -> new PermissionDto(p.getId(), p.getCode(), p.getDescription()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleDto> listRoles() {
        return roleRepository.findAll().stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public RoleDto createRole(CreateRoleRequest request, String actorId) {
        if (roleRepository.findByCode(request.code()).isPresent()) {
            throw ApiException.stateConflict("Role code is already in use.");
        }
        Instant now = Instant.now();
        Role role = new Role(OpaqueIds.generate("role"), request.code(), request.name(),
                request.sessionTimeoutMinutes(), now);
        role.getPermissions().addAll(resolvePermissions(request.permissionCodes()));
        roleRepository.save(role);

        auditService.record(new AuditEntry(actorId, "roles.manage", "role.create", "role", role.getId(),
                null, request, null));
        return toDto(role);
    }

    @Override
    @Transactional
    public RoleDto updateRole(String id, UpdateRoleRequest request, String actorId) {
        Role role = roleRepository.findById(id).orElseThrow(() -> ApiException.notFound("Role not found."));
        ConcurrencyControl.checkVersion(role.getVersion(), request.version());

        if (request.name() != null) {
            role.setName(request.name());
        }
        if (request.sessionTimeoutMinutes() != null) {
            role.setSessionTimeoutMinutes(request.sessionTimeoutMinutes());
        }
        if (request.permissionCodes() != null) {
            role.getPermissions().clear();
            role.getPermissions().addAll(resolvePermissions(request.permissionCodes()));
        }
        role.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(actorId, "roles.manage", "role.update", "role", role.getId(),
                null, request, null));
        return toDto(role);
    }

    private Set<Permission> resolvePermissions(Set<String> codes) {
        Set<Permission> result = new LinkedHashSet<>();
        for (String code : codes) {
            result.add(permissionRepository.findByCode(code)
                    .orElseThrow(() -> ApiException.invalidRequest("Unknown permission code: " + code)));
        }
        return result;
    }

    private RoleDto toDto(Role role) {
        Set<String> permCodes = role.getPermissions().stream().map(Permission::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new RoleDto(role.getId(), role.getCode(), role.getName(), role.getSessionTimeoutMinutes(),
                permCodes, role.getVersion(), role.getCreatedAt(), role.getUpdatedAt());
    }
}
