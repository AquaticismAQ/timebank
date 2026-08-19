package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.error.FieldErrorDetail;
import cn.timebank.iam.application.dto.PermissionDto;
import cn.timebank.iam.application.dto.RoleDto;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleRequest;
import cn.timebank.iam.application.dto.RoleRequests.CreateRoleTemplateRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleRequest;
import cn.timebank.iam.application.dto.RoleRequests.UpdateRoleTemplateRequest;
import cn.timebank.iam.application.dto.RoleTemplateDto;
import cn.timebank.iam.domain.Permission;
import cn.timebank.iam.domain.Role;
import cn.timebank.iam.infrastructure.PermissionRepository;
import cn.timebank.iam.infrastructure.RoleRepository;
import cn.timebank.iam.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
class RoleServiceImpl implements RoleService {

    /**
     * 07-RoleFinetune.md §2.3: reserved for the {@code student} role — a
     * manageable role (protected or custom) can never be granted these,
     * since they only make sense for a self-service submitter, never a
     * staff-type account.
     */
    private static final Set<String> STUDENT_ONLY_PERMISSIONS =
            Set.of("eventProposals.create", "applications.create", "serviceRecords.create");

    /** 07-RoleFinetune.md §2.7: meaningless for a template row — never shown, never read. */
    private static final int TEMPLATE_SESSION_TIMEOUT_PLACEHOLDER = 480;

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    RoleServiceImpl(RoleRepository roleRepository, PermissionRepository permissionRepository,
                     UserRepository userRepository, AuditService auditService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
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
        // 07-RoleFinetune.md §2.7: templates share this table but must never
        // appear as a real, assignable role.
        return roleRepository.findByIsTemplate(false).stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public RoleDto createRole(CreateRoleRequest request, String actorId) {
        if (roleRepository.findByCode(request.code()).isPresent()) {
            throw ApiException.stateConflict("Role code is already in use.");
        }
        rejectStudentOnlyPermissions(request.permissionCodes());
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
        // Content validation before the concurrency check — a request with
        // a forbidden permission is invalid regardless of whether the
        // caller's version happens to be stale, and should always 422, not
        // sometimes surface as a version conflict instead.
        if (request.permissionCodes() != null) {
            rejectStudentOnlyPermissions(request.permissionCodes());
        }
        Role role = roleRepository.findById(id).filter(r -> !r.isTemplate())
                .orElseThrow(() -> ApiException.notFound("Role not found."));
        // 07-RoleFinetune.md §2.6.1: protected roles are now fully locked,
        // not just undeletable — no field (name, permissions, timeout) can
        // change, checked before the version compare for the same reason
        // rejectStudentOnlyPermissions runs first: this isn't a conflict to
        // retry, it's never allowed regardless of version.
        if (role.isProtected()) {
            throw ApiException.stateConflict("The " + role.getCode() + " role is protected and cannot be edited.");
        }
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

    @Override
    @Transactional
    public void deleteRole(String id, String actorId) {
        Role role = roleRepository.findById(id).filter(r -> !r.isTemplate())
                .orElseThrow(() -> ApiException.notFound("Role not found."));
        if (role.isProtected()) {
            throw ApiException.stateConflict("The " + role.getCode() + " role is protected and cannot be deleted.");
        }
        long assignedCount = userRepository.countByRolesId(id);
        if (assignedCount > 0) {
            throw ApiException.stateConflict(
                    assignedCount + " user(s) currently hold this role; reassign them before deleting it.");
        }
        roleRepository.delete(role);
        auditService.record(new AuditEntry(actorId, "roles.manage", "role.delete", "role", id, null, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleTemplateDto> listRoleTemplates() {
        return roleRepository.findByIsTemplate(true).stream().map(this::toTemplateDto).toList();
    }

    @Override
    @Transactional
    public RoleTemplateDto createRoleTemplate(CreateRoleTemplateRequest request, String actorId) {
        rejectStudentOnlyPermissions(request.permissionCodes());
        Instant now = Instant.now();
        // "rtpl" (not "roletmpl"): id/code are VARCHAR(32) and
        // OpaqueIds.generate appends a 26-char suffix + separator, so the
        // prefix budget is tight (31 chars total here) — a longer prefix
        // overflows the column, as an earlier VARCHAR(32) bug this session
        // already hit once with a similar-length prefix.
        String id = OpaqueIds.generate("rtpl");
        // code is unused for a template (RoleTemplateDto never exposes it) —
        // reusing the generated id keeps it trivially unique without a
        // second opaque-id generation just for a dead column.
        Role template = new Role(id, id, request.name(), TEMPLATE_SESSION_TIMEOUT_PLACEHOLDER, now, true);
        template.getPermissions().addAll(resolvePermissions(request.permissionCodes()));
        roleRepository.save(template);

        auditService.record(new AuditEntry(actorId, "roles.manage", "roleTemplate.create", "role", template.getId(),
                null, request, null));
        return toTemplateDto(template);
    }

    @Override
    @Transactional
    public RoleTemplateDto updateRoleTemplate(String id, UpdateRoleTemplateRequest request, String actorId) {
        rejectStudentOnlyPermissions(request.permissionCodes());
        Role template = roleRepository.findById(id).filter(Role::isTemplate)
                .orElseThrow(() -> ApiException.notFound("Role template not found."));
        ConcurrencyControl.checkVersion(template.getVersion(), request.version());

        template.setName(request.name());
        template.getPermissions().clear();
        template.getPermissions().addAll(resolvePermissions(request.permissionCodes()));
        template.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(actorId, "roles.manage", "roleTemplate.update", "role", template.getId(),
                null, request, null));
        return toTemplateDto(template);
    }

    @Override
    @Transactional
    public void deleteRoleTemplate(String id, String actorId) {
        Role template = roleRepository.findById(id).filter(Role::isTemplate)
                .orElseThrow(() -> ApiException.notFound("Role template not found."));
        // Never assigned to a user (§2.7) — no assignment-count guard needed, unlike deleteRole.
        roleRepository.delete(template);
        auditService.record(new AuditEntry(actorId, "roles.manage", "roleTemplate.delete", "role", id, null, null, null));
    }

    /** 07-RoleFinetune.md §2.3: 422, matching contract §2.6's validation-failure shape. */
    private void rejectStudentOnlyPermissions(Set<String> codes) {
        Set<String> rejected = new LinkedHashSet<>(codes);
        rejected.retainAll(STUDENT_ONLY_PERMISSIONS);
        if (!rejected.isEmpty()) {
            List<FieldErrorDetail> fieldErrors = rejected.stream()
                    .map(code -> new FieldErrorDetail("permissionCodes", "RESERVED_FOR_STUDENT",
                            code + " is reserved for the student role."))
                    .toList();
            throw ApiException.validationFailed("Some permissions are reserved for the student role.", fieldErrors);
        }
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
        // One extra query per role, same as deleteRole's own check — fine at
        // this table's size (a handful of roles), not worth batching.
        int userCount = (int) userRepository.countByRolesId(role.getId());
        return new RoleDto(role.getId(), role.getCode(), role.getName(), role.getSessionTimeoutMinutes(),
                permCodes, role.isProtected(), userCount, role.getVersion(), role.getCreatedAt(), role.getUpdatedAt());
    }

    private RoleTemplateDto toTemplateDto(Role template) {
        Set<String> permCodes = template.getPermissions().stream().map(Permission::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new RoleTemplateDto(template.getId(), template.getName(), permCodes, template.getVersion(),
                template.getCreatedAt(), template.getUpdatedAt());
    }
}
