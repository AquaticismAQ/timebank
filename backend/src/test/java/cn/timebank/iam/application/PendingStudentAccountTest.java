package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.domain.Role;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.iam.infrastructure.RoleRepository;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.organization.application.FeatureFlagService;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 10-StudentIdentityAndOnboarding.md §3.3 and the guarantee §2.4 leans on:
 * "an account with no password cannot be logged into, so no account is ever
 * usable before its slip has been delivered."
 *
 * <p>The authentication half is checked through a real
 * {@link DaoAuthenticationProvider} rather than by asserting on a boolean
 * field, because the boolean is only interesting if Spring Security actually
 * acts on it. {@link #an_active_account_with_a_password_still_authenticates()}
 * is the control: without it, a misconfigured provider that rejects
 * everything would make the negative cases pass for the wrong reason.</p>
 */
class PendingStudentAccountTest {

    private static final String STUDENT_ID = "std_0000000000000000000000001";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private UserServiceImpl service(int drawnUsername) {
        Role studentRole = new Role("role_student", "student", "Student", 720, Instant.now());
        when(roleRepository.findByCodeAndIsTemplateFalse("student")).thenReturn(Optional.of(studentRole));
        StudentUsernameGenerator generator = new StudentUsernameGenerator(userRepository, () -> drawnUsername);
        return new UserServiceImpl(userRepository, roleRepository, passwordEncoder, auditService,
                mock(FeatureFlagService.class), mock(StudentService.class), generator,
                5, 15, 480);
    }

    private User captureCreatedAccount() {
        when(userRepository.findByStudentId(STUDENT_ID)).thenReturn(Optional.empty());
        service(31415926).createPendingStudentAccount(STUDENT_ID, "Example Student", "zh-CN", "usr_admin");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Test
    void an_imported_account_is_created_pending_with_no_password() {
        User created = captureCreatedAccount();

        assertThat(created.getPasswordHash()).isNull();
        assertThat(created.hasUsableCredential()).isFalse();
        assertThat(created.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        // Not LOCKED, which §2.6 reserves for brute-force lockout, and not
        // INACTIVE, which an admin sets deliberately on a real account.
        assertThat(created.getStatus()).isNotIn(UserStatus.LOCKED, UserStatus.INACTIVE, UserStatus.ACTIVE);
        assertThat(created.getStudentId()).isEqualTo(STUDENT_ID);
        assertThat(created.getUsername()).isEqualTo("31415926");
        assertThat(created.isMustChangePassword()).isFalse();
        assertThat(created.getRoles()).extracting(Role::getCode).containsExactly("student");
    }

    @Test
    void creating_the_account_is_audited() {
        captureCreatedAccount();

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(entry.capture());
        assertThat(entry.getValue().actorId()).isEqualTo("usr_admin");
        assertThat(entry.getValue().resourceType()).isEqualTo("user");
        assertThat(entry.getValue().action()).isEqualTo("user.createPending");
    }

    @Test
    void a_student_who_already_has_an_account_does_not_get_a_second_one() {
        User existing = User.pendingActivation("usr_existing", "45454545", "Example Student",
                STUDENT_ID, "zh-CN", Instant.now());
        when(userRepository.findByStudentId(STUDENT_ID)).thenReturn(Optional.of(existing));

        UserService.PendingStudentAccount result = service(31415926)
                .createPendingStudentAccount(STUDENT_ID, "Example Student", "zh-CN", "usr_admin");

        assertThat(result.username()).isEqualTo("45454545");
        verify(userRepository, org.mockito.Mockito.never()).saveAndFlush(any(User.class));
    }

    @Test
    void a_pending_account_is_reported_as_not_authenticatable() {
        User pending = User.pendingActivation("usr_pending", "31415926", "Example Student",
                STUDENT_ID, "zh-CN", Instant.now());
        when(userRepository.findByUsername("31415926")).thenReturn(Optional.of(pending));

        AuthenticatedUserView view = service(31415926).findByUsernameForAuth("31415926").orElseThrow();

        assertThat(view.passwordHash()).isNull();
        assertThat(view.active()).isFalse();
        assertThat(new CookieSessionUserDetails(view).isEnabled()).isFalse();
    }

    @Test
    void a_pending_account_cannot_log_in() {
        User pending = User.pendingActivation("usr_pending", "31415926", "Example Student",
                STUDENT_ID, "zh-CN", Instant.now());

        assertThatThrownBy(() -> authenticate(pending, "Whatever-They-Type-5"))
                .isInstanceOf(DisabledException.class);
    }

    /**
     * The case that separates "status is not ACTIVE" from "there is no
     * credential": an account flipped to ACTIVE by hand while still having no
     * password. If the auth view ever went back to testing status alone, this
     * account would be enabled and the provider would reach the encoder with
     * a null hash — so this test fails on exactly that regression, while
     * {@link #a_pending_account_cannot_log_in()} would not.
     */
    @Test
    void an_account_flipped_to_active_without_a_password_still_cannot_log_in() {
        User pending = User.pendingActivation("usr_pending", "31415926", "Example Student",
                STUDENT_ID, "zh-CN", Instant.now());
        pending.setStatus(UserStatus.ACTIVE);

        assertThatThrownBy(() -> authenticate(pending, "Whatever-They-Type-5"))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void an_active_account_with_a_password_still_authenticates() {
        User active = User.pendingActivation("usr_active", "31415926", "Example Student",
                STUDENT_ID, "zh-CN", Instant.now());
        active.setPasswordHash(passwordEncoder.encode("chosen-at-activation"));
        active.setStatus(UserStatus.ACTIVE);

        Authentication result = authenticate(active, "chosen-at-activation");

        assertThat(result.isAuthenticated()).isTrue();
    }

    /** Runs the real Spring Security credential check the login endpoint runs. */
    private Authentication authenticate(User user, String presentedPassword) {
        when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
        UserServiceImpl userService = service(31415926);
        UserDetailsService userDetailsService = username -> userService.findByUsernameForAuth(username)
                .map(CookieSessionUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + username));

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider.authenticate(
                new UsernamePasswordAuthenticationToken(user.getUsername(), presentedPassword));
    }
}
