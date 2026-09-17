package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.MeResponse;
import cn.timebank.iam.application.dto.UpdateProfileRequest;
import cn.timebank.iam.application.dto.UpdateUserRequest;
import cn.timebank.iam.application.dto.UserDirectoryEntryDto;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.application.dto.UserListFilter;
import cn.timebank.iam.domain.PasswordPolicy;
import cn.timebank.iam.domain.Role;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.iam.infrastructure.RoleRepository;
import cn.timebank.iam.infrastructure.UserListQueryRepository;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.organization.application.FeatureFlagService;
import cn.timebank.organization.application.dto.FeatureFlagsDto;
import cn.timebank.student.application.StudentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final UserListQueryRepository userListQueryRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final FeatureFlagService featureFlagService;
    private final StudentService studentService;
    private final StudentUsernameGenerator studentUsernameGenerator;

    /** The seeded role every imported student account is given (R__seed_permissions_and_roles.sql). */
    private static final String STUDENT_ROLE_CODE = "student";

    private final int maxFailedAttempts;
    private final int lockoutDurationMinutes;
    private final int defaultSessionTimeoutMinutes;

    UserServiceImpl(UserRepository userRepository, UserListQueryRepository userListQueryRepository,
                     RoleRepository roleRepository,
                     PasswordEncoder passwordEncoder, AuditService auditService, FeatureFlagService featureFlagService,
                     StudentService studentService, StudentUsernameGenerator studentUsernameGenerator,
                     @Value("${timebank.auth.lockout.max-failed-attempts:5}") int maxFailedAttempts,
                     @Value("${timebank.auth.lockout.duration-minutes:15}") int lockoutDurationMinutes,
                     @Value("${timebank.auth.session.default-timeout-minutes:480}") int defaultSessionTimeoutMinutes) {
        this.featureFlagService = featureFlagService;
        this.userRepository = userRepository;
        this.userListQueryRepository = userListQueryRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.studentService = studentService;
        this.studentUsernameGenerator = studentUsernameGenerator;
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

    /**
     * Issue 094. Delegates to the read-model query rather than {@code
     * userRepository.findAll} because the list is filterable and sortable by
     * the linked student's grade and class, which live in another module's
     * table — see {@link UserListQueryRepository}'s class Javadoc. The rows it
     * returns already carry grade/class, so no {@link #withPlacement}
     * enrichment is needed (and no per-row student lookup happens).
     */
    @Override
    @Transactional(readOnly = true)
    public Page<UserDto> list(Pageable pageable, UserListFilter filter) {
        UserListFilter effective = filter != null ? filter : UserListFilter.NONE;
        List<UserDto> rows = userListQueryRepository.queryPage(effective, pageable);
        return new PageImpl<>(rows, pageable, userListQueryRepository.countMatching(effective));
    }

    @Override
    @Transactional
    public UserDto create(CreateUserRequest request, String actorId) {
        if (userRepository.existsByUsername(request.username())) {
            throw ApiException.stateConflict("Username is already taken.");
        }
        if (request.studentId() != null) {
            requireUnlinkedStudent(request.studentId(), null);
        }
        if (request.staffId() != null && userRepository.existsByStaffId(request.staffId())) {
            throw ApiException.stateConflict("Staff ID is already in use.");
        }
        PasswordPolicy.validate(request.initialPassword(), request.username());
        Role role = resolveRole(request.roleCode());
        Instant now = Instant.now();
        User user = new User(OpaqueIds.generate("usr"), request.username(),
                passwordEncoder.encode(request.initialPassword()), request.displayName(), request.email(),
                request.preferredLanguage() != null ? request.preferredLanguage() : "zh-CN", now);
        user.setStudentId(request.studentId());
        user.setStaffId(request.staffId());
        user.addRole(role);
        userRepository.save(user);

        auditService.record(new AuditEntry(actorId, "users.manage", "user.create", "user", user.getId(),
                null, new UserSummaryForAudit(user), null));
        return toDto(user);
    }

    @Override
    @Transactional
    public PendingStudentAccount createPendingStudentAccount(String studentId, String displayName,
                                                              String preferredLanguage, String actorId) {
        // Idempotent by student, not by request: a resumed or re-run import
        // commit must not mint a second login for somebody who already has one.
        Optional<User> existing = userRepository.findByStudentId(studentId);
        if (existing.isPresent()) {
            return new PendingStudentAccount(existing.get().getId(), existing.get().getUsername());
        }
        Role role = resolveRole(STUDENT_ROLE_CODE);
        Instant now = Instant.now();
        User user = User.pendingActivation(OpaqueIds.generate("usr"), studentUsernameGenerator.generate(),
                displayName, studentId, preferredLanguage != null ? preferredLanguage : "zh-CN", now);
        user.addRole(role);
        // Flushed here, not at transaction end: the username pre-check cannot
        // see a concurrent import's uncommitted row, so uk_user_username is the
        // real guarantee. Flushing makes a violation surface on this row, where
        // the import runner reports it, instead of at commit time where it
        // would fail the whole job with no indication of which student.
        userRepository.saveAndFlush(user);

        auditService.record(new AuditEntry(actorId, "users.manage", "user.createPending", "user", user.getId(),
                null, new UserSummaryForAudit(user), null));
        return new PendingStudentAccount(user.getId(), user.getUsername());
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
        if (request.roleCode() != null) {
            user.getRoles().clear();
            user.addRole(resolveRole(request.roleCode()));
        }
        if (request.studentId() != null) {
            requireUnlinkedStudent(request.studentId(), user.getId());
            user.setStudentId(request.studentId());
        }
        if (request.staffId() != null) {
            if (userRepository.existsByStaffIdAndIdNot(request.staffId(), user.getId())) {
                throw ApiException.stateConflict("Staff ID is already in use.");
            }
            user.setStaffId(request.staffId());
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

    /**
     * §3.7. <b>Clears</b> the credential rather than replacing it — there is no
     * {@code generateTemporaryPassword} here any more, and that absence is the
     * point (issue 088).
     *
     * <p>Two refusals, neither of them an edge case dressed up as one:</p>
     *
     * <ul>
     *   <li><b>Self-reset.</b> Only an authenticated admin can reach this, so
     *       an admin resetting their own account cannot be doing it because
     *       they forgot the password — they just used it. What is left is "I
     *       may be compromised", and {@code POST /me/change-password} answers
     *       that better: it also revokes every session, and it leaves them
     *       holding a working login. Allowing it instead would let the last
     *       remaining admin clear their own password and depend on a PDF
     *       download completing to ever get back in — and nobody else can
     *       issue them a slip.</li>
     *   <li><b>An {@code INACTIVE} account.</b> Deactivation is a deliberate
     *       decision; moving the account to {@code PENDING_ACTIVATION} would
     *       quietly undo it and hand a redeemable code to an account somebody
     *       switched off. {@code ActivationServiceImpl.redeem} refuses
     *       {@code INACTIVE} for the same reason, and {@code
     *       CredentialSlipServiceImpl.ISSUABLE_STATUSES} excludes it from a
     *       class batch. Reactivation first, then a reset.</li>
     * </ul>
     *
     * <p>{@code LOCKED} is deliberately <em>not</em> refused: a brute-force
     * lockout is the single most common reason a reset is requested at all, so
     * the lock counters are cleared here the same way {@link #unlock} clears
     * them.</p>
     */
    @Override
    @Transactional
    public ResetAccount resetToPendingActivation(String id, String actorId) {
        User user = findOrThrow(id);
        if (user.getId().equals(actorId)) {
            throw ApiException.stateConflict(
                    "You cannot reset your own account. Change your own password instead, "
                            + "or ask another administrator to reset it for you.");
        }
        if (user.getStatus() == UserStatus.INACTIVE) {
            throw ApiException.stateConflict(
                    "This account is deactivated. Reactivate it first, then reset it.");
        }

        CredentialSummary before = new CredentialSummary(user);
        user.setPasswordHash(null);
        // The user chooses their own password at activation, so there is
        // nothing for them to be forced to change afterwards.
        user.setMustChangePassword(false);
        user.setStatus(UserStatus.PENDING_ACTIVATION);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(Instant.now());

        // The `after` summary is what makes this row readable a year later:
        // hasPassword flipping true → false is the whole event, and it is
        // stated rather than implied by the status. No credential material of
        // any kind — there is none to leak now, and this row must not become
        // the place one reappears.
        auditService.record(new AuditEntry(actorId, "users.manage", "user.adminResetCredential", "user", user.getId(),
                before, new CredentialSummary(user), "ADMIN_RESET"));
        return new ResetAccount(user.getId(), user.getUsername(), user.getDisplayName());
    }

    @Override
    @Transactional
    public void changeOwnPassword(String userId, String currentPassword, String newPassword) {
        User user = findOrThrow(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.invalidRequest("Current password is incorrect.");
        }
        PasswordPolicy.validate(newPassword, user.getUsername());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(userId, null, "user.changePassword", "user", user.getId(),
                null, null, "SELF_SERVICE"));
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
                new MeResponse.UserSummary(user.getId(), user.getDisplayName(), roleCodes, permissionCodes, user.getStudentId(), Set.of()),
                deriveWorkspaces(roleCodes),
                user.getPreferredLanguage(),
                new MeResponse.FeatureFlags(flags.pov(), flags.redemption(), flags.borrowing(), flags.appreciation()));
    }

    private User findOrThrow(String id) {
        return userRepository.findById(id).orElseThrow(() -> ApiException.notFound("User not found."));
    }

    /**
     * {@code excludeUserId} is {@code null} on create (nothing to exclude
     * yet) or the account's own id on update (re-submitting its own current
     * link isn't a conflict with itself). Existence uses {@link
     * StudentService#displayNamesOf}, the same "absent id means it doesn't
     * exist" convention that method's own Javadoc documents — no dedicated
     * exists-by-id method needed.
     */
    private void requireUnlinkedStudent(String studentId, String excludeUserId) {
        if (!studentService.displayNamesOf(List.of(studentId)).containsKey(studentId)) {
            throw ApiException.invalidRequest("Unknown student: " + studentId);
        }
        boolean alreadyLinked = excludeUserId == null
                ? userRepository.existsByStudentId(studentId)
                : userRepository.existsByStudentIdAndIdNot(studentId, excludeUserId);
        if (alreadyLinked) {
            throw ApiException.stateConflict("This student is already linked to another account.");
        }
    }

    private Role resolveRole(String roleCode) {
        // 07-RoleFinetune.md §2.7: findByCodeAndIsTemplateFalse, not
        // findByCode — a role template's code must never be assignable to a
        // user, even if guessed, despite sharing the same table.
        return roleRepository.findByCodeAndIsTemplateFalse(roleCode)
                .orElseThrow(() -> ApiException.invalidRequest("Unknown role code: " + roleCode));
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

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findUserIdByStudentId(String studentId) {
        return userRepository.findByStudentId(studentId).map(User::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StudentAccount> studentAccountsOf(List<String> studentIds) {
        if (studentIds.isEmpty()) {
            return List.of();
        }
        return userRepository.findByStudentIdIn(studentIds).stream()
                .map(u -> new StudentAccount(u.getStudentId(), u.getId(), u.getUsername(), u.getStatus().name()))
                .toList();
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

    @Override
    @Transactional(readOnly = true)
    public boolean hasActivePermission(String userId, String permissionCode) {
        return userRepository.existsActiveByIdAndPermissionCode(userId, permissionCode);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserPermissionHolder> activeUsersWithPermission(String permissionCode) {
        return userRepository.findActiveWithPermission(permissionCode).stream()
                .map(p -> new UserPermissionHolder(p.getUserId(), p.getRoleCode()))
                .toList();
    }

    private AuthenticatedUserView toAuthView(User user) {
        Set<String> roleCodes = user.getRoles().stream().map(Role::getCode).collect(Collectors.toCollection(LinkedHashSet::new));
        // `active` is deliberately status AND a stored credential, not status
        // alone (10-StudentIdentityAndOnboarding.md §2.4): a never-activated
        // import has no password_hash, and an account that cannot satisfy a
        // password check must be refused at the pre-authentication check
        // rather than reaching the encoder with a null hash. This also means
        // flipping such an account to ACTIVE by hand does not make it usable —
        // activation is "the password was set" (§2.6), nothing less.
        return new AuthenticatedUserView(
                user.getId(), user.getUsername(), user.getPasswordHash(), user.getDisplayName(),
                user.getStatus() == UserStatus.ACTIVE && user.hasUsableCredential(),
                user.isLocked(), user.getPreferredLanguage(),
                roleCodes, permissionCodesOf(user), user.getStudentId(),
                user.sessionTimeoutMinutes(defaultSessionTimeoutMinutes));
    }

    // scopeGradeIds is always empty here — this module can't reach security.application
    // (see UserDto.withScopeGradeIds); the web layer fills it in.
    //
    // Grade/class are a different story and are filled in right here: `student`
    // has no dependency on `iam` in any layer, so calling StudentService from
    // this module adds no cycle — this class already holds one for
    // requireUnlinkedStudent.
    private UserDto toDto(User user) {
        String roleCode = soleRoleCodeOf(user);
        UserDto dto = new UserDto(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getStudentId(), user.getStaffId(), user.getStatus().name(), user.getPreferredLanguage(), roleCode,
                user.getVersion(), user.getCreatedAt(), user.getUpdatedAt(), Set.of(),
                // lastLoginAt is null on single-user responses by design — see
                // UserDto. Only the list read model, which displays the column,
                // pays for the login_log lookup.
                null, null, null, null, null);
        return withPlacement(dto, user.getStudentId());
    }

    /**
     * Issue 094: a student-role account shows the grade/class of the student
     * it is linked to. A staff account has no {@code studentId} and keeps all
     * four fields null — its grade bond is {@code scopeGradeIds}, a different
     * field with a different meaning (see {@code UserDto}).
     */
    private UserDto withPlacement(UserDto dto, String studentId) {
        if (studentId == null || studentId.isBlank()) {
            return dto;
        }
        StudentService.StudentPlacement placement = studentService.placementsOf(List.of(studentId)).get(studentId);
        if (placement == null) {
            return dto;
        }
        return new UserDto(dto.id(), dto.username(), dto.displayName(), dto.email(), dto.studentId(), dto.staffId(),
                dto.status(), dto.preferredLanguage(), dto.roleCode(), dto.version(), dto.createdAt(), dto.updatedAt(),
                dto.scopeGradeIds(), placement.gradeId(), placement.gradeLevel(), placement.classId(),
                placement.className(), dto.lastLoginAt());
    }

    /** 07-RoleFinetune.md §2.5: every user holds exactly one role — {@code null} only for a not-yet-migrated account. */
    private String soleRoleCodeOf(User user) {
        return user.getRoles().stream().map(Role::getCode).findFirst().orElse(null);
    }

    /**
     * §3.7's before/after pair. Two booleans and a status, never a hash and
     * never a password — an audit row read by everyone holding
     * {@code auditLogs.read} is the last place credential material belongs.
     */
    private record CredentialSummary(String status, boolean hasPassword, boolean mustChangePassword) {
        CredentialSummary(User user) {
            this(user.getStatus().name(), user.hasUsableCredential(), user.isMustChangePassword());
        }
    }

    /** Compact before/after summary for audit rows — never the entity itself (Design.md §13). */
    private record UserSummaryForAudit(String username, String displayName, String status, Set<String> roleCodes) {
        UserSummaryForAudit(User user) {
            this(user.getUsername(), user.getDisplayName(), user.getStatus().name(),
                    user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet()));
        }
    }
}
