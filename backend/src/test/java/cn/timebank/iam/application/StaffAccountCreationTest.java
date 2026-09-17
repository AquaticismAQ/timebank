package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.domain.Role;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.iam.infrastructure.RoleRepository;
import cn.timebank.iam.infrastructure.UserListQueryRepository;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.organization.application.FeatureFlagService;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue 096 §3/§4 — a hand-created staff account is born exactly like an
 * imported student's: {@code PENDING_ACTIVATION}, no stored hash, unusable
 * until a slip is redeemed.
 *
 * <p>{@link PendingStudentAccountTest} makes the same guarantee for the roster
 * import. This is its twin for the other door into the {@code user} table, and
 * it exists because that door was open for months: the form took an
 * administrator-typed {@code initialPassword}, so §2.4's "no password plaintext
 * exists at any point" held for students and quietly failed for every teacher.
 * A regression here would look completely normal on screen — an account gets
 * created, an admin can log in as nobody — which is why the check is on the
 * saved row and on a real authentication attempt, not on the form.</p>
 */
class StaffAccountCreationTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    private UserServiceImpl service() {
        Role teacherRole = new Role("role_teacher", "teacher", "Teacher", 480, Instant.now());
        when(roleRepository.findByCodeAndIsTemplateFalse("teacher")).thenReturn(Optional.of(teacherRole));
        return new UserServiceImpl(userRepository, mock(UserListQueryRepository.class), roleRepository,
                passwordEncoder, auditService, mock(FeatureFlagService.class), mock(StudentService.class),
                new StudentUsernameGenerator(userRepository, () -> 31415926), 5, 15, 480);
    }

    private User create(CreateUserRequest request) {
        service().create(request, "usr_admin");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        return saved.getValue();
    }

    private static CreateUserRequest teacher(String username, String email) {
        return new CreateUserRequest(username, "Example Teacher", email, "teacher", "zh-CN",
                Set.of("grade_7"), null, "staff-0001");
    }

    @Test
    void a_created_teacher_has_no_password_and_is_pending_activation() {
        User created = create(teacher("t.wei", "t.wei@example-township-school.edu"));

        assertThat(created.getPasswordHash()).isNull();
        assertThat(created.hasUsableCredential()).isFalse();
        assertThat(created.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(created.getStatus()).isNotIn(UserStatus.ACTIVE, UserStatus.LOCKED, UserStatus.INACTIVE);
        // Nothing to be forced to change: they will choose the password themselves.
        assertThat(created.isMustChangePassword()).isFalse();
        assertThat(created.getRoles()).extracting(Role::getCode).containsExactly("teacher");
        assertThat(created.getStaffId()).isEqualTo("staff-0001");
        // A staff account is not a student one, whatever the factory is named.
        assertThat(created.getStudentId()).isNull();
    }

    /**
     * The username is whatever was asked for, not {@code email.split("@")[0]}.
     * The form derived one from the other, which is why an optional field was
     * marked required — and why changing an address left a username that no
     * longer resembled it (issue 096 §1).
     */
    @Test
    void the_username_is_the_one_given_and_is_not_derived_from_the_email() {
        User created = create(teacher("wchen2031", "completely.different@example-township-school.edu"));

        assertThat(created.getUsername()).isEqualTo("wchen2031");
        assertThat(created.getEmail()).isEqualTo("completely.different@example-township-school.edu");
    }

    @Test
    void an_account_can_be_created_with_no_email_at_all() {
        User created = create(teacher("wchen2032", null));

        assertThat(created.getEmail()).isNull();
        assertThat(created.getUsername()).isEqualTo("wchen2032");
    }

    /**
     * The guarantee itself, through the real credential check rather than a
     * field assertion: an account that has just been created cannot be logged
     * into by anyone, including the administrator who created it.
     */
    @Test
    void the_created_account_cannot_be_logged_into() {
        User created = create(teacher("wchen2033", null));
        when(userRepository.findByUsername("wchen2033")).thenReturn(Optional.of(created));

        assertThatThrownBy(() -> authenticate("wchen2033", "Whatever-They-Type-5"))
                .isInstanceOf(DisabledException.class);
    }

    /**
     * The control for the test above — the same provider, the same service,
     * an account that does have a password. Without it, a provider wired to
     * reject everything would make the negative case pass for the wrong reason.
     */
    @Test
    void the_same_account_logs_in_once_it_has_chosen_a_password() {
        User created = create(teacher("wchen2034", null));
        created.setPasswordHash(passwordEncoder.encode("Chosen-At-Activation-9"));
        created.setStatus(UserStatus.ACTIVE);
        when(userRepository.findByUsername("wchen2034")).thenReturn(Optional.of(created));

        assertThat(authenticate("wchen2034", "Chosen-At-Activation-9").isAuthenticated()).isTrue();
    }

    /**
     * The field cannot come back by accident. A reintroduced
     * {@code initialPassword} would compile, pass every test above (they never
     * set it), and silently restore the administrator-typed password the whole
     * activation flow exists to remove — the same shape of regression the
     * self-service-recovery guard blocks.
     */
    @Test
    void the_create_request_carries_no_password_field_at_all() {
        assertThat(CreateUserRequest.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .noneSatisfy(name -> assertThat(name.toLowerCase()).contains("password"));
    }

    @Test
    void the_returned_dto_reports_the_pending_status_so_the_ui_cannot_show_it_as_ready() {
        UserDto dto = service().create(teacher("wchen2035", null), "usr_admin");

        assertThat(dto.status()).isEqualTo(UserStatus.PENDING_ACTIVATION.name());
    }

    private Authentication authenticate(String username, String presentedPassword) {
        UserServiceImpl userService = service();
        UserDetailsService userDetailsService = name -> userService.findByUsernameForAuth(name)
                .map(CookieSessionUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + name));

        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider.authenticate(new UsernamePasswordAuthenticationToken(username, presentedPassword));
    }
}
