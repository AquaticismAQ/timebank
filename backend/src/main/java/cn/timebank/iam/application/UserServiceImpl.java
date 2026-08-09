package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.dto.AuthDtos;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.MeResponse;
import cn.timebank.iam.application.dto.UpdateProfileRequest;
import cn.timebank.iam.application.dto.UpdateUserRequest;
import cn.timebank.iam.application.dto.UserDirectoryEntryDto;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.domain.Role;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.iam.infrastructure.RoleRepository;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.organization.application.FeatureFlagService;
import cn.timebank.organization.application.dto.FeatureFlagsDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deliberately does not depend on {@code security.application.SessionRevocationService}
 * — {@code iam} and {@code security} would otherwise depend on each other
 * ({@code security}'s {@code UserDetailsServiceImpl} already depends on
 * {@code iam.application}), which is a module cycle. Every method here whose
 * contract requires killing live sessions (deactivation, admin reset, self
 * password change, token-based reset) returns enough information — the
 * username — for its caller in {@code iam.web} to invoke revocation itself,
 * after this method returns. The web layer, not this service, is where
 * "iam" and "security" are allowed to meet.
 */
@Service
class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final FeatureFlagService featureFlagService;

    private final int maxFailedAttempts;
    private final int lockoutDurationMinutes;
    private final int defaultSessionTimeoutMinutes;

    UserServiceImpl(UserRepository userRepository, RoleRepository roleRepository,
                     PasswordEncoder passwordEncoder, AuditService auditService, FeatureFlagService featureFlagService,
                     @Value("${timebank.auth.lockout.max-failed-attempts:5}") int maxFailedAttempts,
                     @Value("${timebank.auth.lockout.duration-minutes:15}") int lockoutDurationMinutes,
                     @Value("${timebank.auth.session.default-timeout-minutes:480}") int defaultSessionTimeoutMinutes) {
        this.featureFlagService = featureFlagService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutDurationMinutes = lockoutDurationMinutes;
        this.defaultSessionTimeoutMinutes = defaultSessionTimeoutMinutes;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticatedUserView> findByUsernameForAuth(String username) {
        return userRepository.findByUsername(username).map(this::toAuthView);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto getById(String id) {
        return toDto(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserDto> list(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toDto);
    }

    @Override
    @Transactional
    public UserDto create(CreateUserRequest request, String actorId) {
        if (userRepository.existsByUsername(request.username())) {
            throw ApiException.stateConflict("Username is already taken.");
        }
        Set<Role> roles = resolveRoles(request.roleCodes());
        Instant now = Instant.now();
        User user = new User(OpaqueIds.generate("usr"), request.username(),
                passwordEncoder.encode(request.initialPassword()), request.displayName(), request.email(),
                request.preferredLanguage() != null ? request.preferredLanguage() : "zh-CN", now);
        roles.forEach(user::addRole);
        userRepository.save(user);

        auditService.record(new AuditEntry(actorId, "users.manage", "user.create", "user", user.getId(),
                null, new UserSummaryForAudit(user), null));
        return toDto(user);
    }

    @Override
    @Transactional
    public UserDto update(String id, UpdateUserRequest request, String actorId) {
        User user = findOrThrow(id);
        ConcurrencyControl.checkVersion(user.getVersion(), request.version());

        UserSummaryForAudit before = new UserSummaryForAudit(user);
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.status() != null) {
            user.setStatus(parseStatus(request.status()));
        }
        if (request.roleCodes() != null) {
            user.getRoles().clear();
            resolveRoles(request.roleCodes()).forEach(user::addRole);
        }
        user.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(actorId, "users.manage", "user.update", "user", user.getId(),
                before, new UserSummaryForAudit(user), null));
        return toDto(user);
    }

    @Override
    @Transactional
    public UserDto updateProfile(String id, UpdateProfileRequest request) {
        User user = findOrThrow(id);
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.preferredLanguage() != null) {
            user.setPreferredLanguage(request.preferredLanguage());
        }
        user.setUpdatedAt(Instant.now());
        return toDto(user);
    }

    @Override
    @Transactional
    public void unlock(String id, String actorId) {
        User user = findOrThrow(id);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "users.manage", "user.unlock", "user", user.getId(),
                null, null, null));
    }

    @Override
    @Transactional
    public AuthDtos.AdminResetPasswordResponse adminResetPassword(String id, String actorId) {
        User user = findOrThrow(id);
        String temporaryPassword = generateTemporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(actorId, "users.manage", "user.adminResetPassword", "user", user.getId(),
                null, null, "ADMIN_RESET"));
        return new AuthDtos.AdminResetPasswordResponse(user.getUsername(), temporaryPassword);
    }

    @Override
    @Transactional
    public void changeOwnPassword(String userId, String currentPassword, String newPassword) {
        User user = findOrThrow(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.invalidRequest("Current password is incorrect.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(userId, null, "user.changePassword", "user", user.getId(),
                null, null, "SELF_SERVICE"));
    }

    @Override
    @Transactional
    public String resetPasswordWithToken(String userId, String newPassword) {
        User user = findOrThrow(userId);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(userId, null, "user.resetPassword", "user", user.getId(),
                null, null, "FORGOT_PASSWORD_TOKEN"));
        return user.getUsername();
    }

    @Override
    @Transactional
    public void recordSuccessfulLogin(String userId) {
        User user = findOrThrow(userId);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(Instant.now());
    }

    @Override
    @Transactional
    public boolean recordFailedLogin(String userId) {
        User user = findOrThrow(userId);
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        user.setUpdatedAt(Instant.now());
        if (attempts >= maxFailedAttempts) {
            user.setLockedUntil(Instant.now().plus(lockoutDurationMinutes, ChronoUnit.MINUTES));
            return true;
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public MeResponse buildMeResponse(String userId) {
        User user = findOrThrow(userId);
        Set<String> roleCodes = user.getRoles().stream().map(Role::getCode).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> permissionCodes = permissionCodesOf(user);
        FeatureFlagsDto flags = featureFlagService.getFlags();
        return new MeResponse(
                new MeResponse.UserSummary(user.getId(), user.getDisplayName(), roleCodes, permissionCodes, user.getStudentId()),
                deriveWorkspaces(roleCodes),
                user.getPreferredLanguage(),
                new MeResponse.FeatureFlags(flags.pov(), flags.redemption(), flags.borrowing(), flags.appreciation()));
    }

    private User findOrThrow(String id) {
        return userRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
    }

    private Set<Role> resolveRoles(Set<String> roleCodes) {
        Set<Role> roles = new LinkedHashSet<>();
        for (String code : roleCodes) {
            roles.add(roleRepository.findByCode(code)
                    .orElseThrow(() -> ApiException.invalidRequest("Unknown role code: " + code)));
        }
        return roles;
    }

    private UserStatus parseStatus(String raw) {
        try {
            return UserStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Unknown user status: " + raw);
        }
    }

    private Set<String> permissionCodesOf(User user) {
        return user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(cn.timebank.iam.domain.Permission::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** {@code student} vs {@code admin} is the frontend's fixed two-value workspace vocabulary, not a school-configurable one. */
    private Set<String> deriveWorkspaces(Set<String> roleCodes) {
        Set<String> workspaces = new LinkedHashSet<>();
        if (roleCodes.contains("student")) {
            workspaces.add("student");
        }
        if (roleCodes.stream().anyMatch(c -> !"student".equals(c))) {
            workspaces.add("admin");
        }
        return workspaces;
    }

    private String generateTemporaryPassword() {
        SecureRandom random = new SecureRandom();
        String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789!@#%";
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findUserIdByStudentId(String studentId) {
        return userRepository.findByStudentId(studentId).map(User::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> displayNamesOf(List<String> userIds) {
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getDisplayName));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDirectoryEntryDto> searchDirectory(String roleCode, String search) {
        if (roleCode == null || roleCode.isBlank() || search == null || search.isBlank()) {
            return List.of();
        }
        return userRepository.searchActiveByRoleAndDisplayNameContaining(roleCode, search, PageRequest.of(0, 20)).stream()
                .map(u -> new UserDirectoryEntryDto(u.getId(), u.getDisplayName()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveRole(String userId, String roleCode) {
        return userRepository.existsActiveByIdAndRoleCode(userId, roleCode);
    }

    private AuthenticatedUserView toAuthView(User user) {
        Set<String> roleCodes = user.getRoles().stream().map(Role::getCode).collect(Collectors.toCollection(LinkedHashSet::new));
        return new AuthenticatedUserView(
                user.getId(), user.getUsername(), user.getPasswordHash(), user.getDisplayName(),
                user.getStatus() == UserStatus.ACTIVE, user.isLocked(), user.getPreferredLanguage(),
                roleCodes, permissionCodesOf(user), user.getStudentId(),
                user.sessionTimeoutMinutes(defaultSessionTimeoutMinutes));
    }

    // scopeGradeIds is always empty here — this module can't reach security.application
    // (see UserDto.withScopeGradeIds); the web layer fills it in.
    private UserDto toDto(User user) {
        Set<String> roleCodes = user.getRoles().stream().map(Role::getCode).collect(Collectors.toCollection(LinkedHashSet::new));
        return new UserDto(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getStudentId(), user.getStatus().name(), user.getPreferredLanguage(), roleCodes,
                user.getVersion(), user.getCreatedAt(), user.getUpdatedAt(), Set.of());
    }

    /** Compact before/after summary for audit rows — never the entity itself (Design.md §13). */
    private record UserSummaryForAudit(String username, String displayName, String status, Set<String> roleCodes) {
        UserSummaryForAudit(User user) {
            this(user.getUsername(), user.getDisplayName(), user.getStatus().name(),
                    user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet()));
        }
    }
}
