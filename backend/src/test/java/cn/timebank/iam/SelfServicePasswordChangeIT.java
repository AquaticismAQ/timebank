package cn.timebank.iam;

import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.ActivationService;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Self-service password change (issue 111) — {@code POST /me/change-password}'s
 * service half.
 *
 * <p>Deliberately not added to {@link AuthFlowIT}, which already covers session
 * revocation on change: that class is one of the eight currently red on {@code
 * main} (`Docs/issues/109`), because its fixture passes the literal {@code
 * "system"} as {@code activation_code.issued_by} and {@code V27} made that column
 * a foreign key. A new assertion added there would not run. The fixture here
 * issues the activation code from a <em>real</em> user row instead, which is also
 * the shape issue 109's eventual fix needs.</p>
 */
class SelfServicePasswordChangeIT extends AbstractIntegrationTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger();
    private static final String GOOD = "FirstChoice123!x";

    @Autowired
    private UserService userService;
    @Autowired
    private ActivationService activationService;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void theCurrentPasswordIsRequiredAndAWrongOneChangesNothing() {
        UserDto user = activatedUser("pw_change_wrong");

        assertThatThrownBy(() -> userService.changeOwnPassword(user.id(), "NotThePassword1!", "NewChoice456!Ab"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Current password is incorrect");

        // The old password still works, so the failed attempt really changed
        // nothing — an exception alone would not prove that.
        assertThat(authenticates(user.username(), GOOD)).isTrue();
    }

    /**
     * The policy is the server's, not the form's. The browser refuses a weak
     * password before submitting (`ChangePasswordDialog`), and that is a
     * courtesy — this asserts the control behind it.
     */
    @Test
    void aWeakNewPasswordIsRefused() {
        UserDto user = activatedUser("pw_change_weak");

        assertThatThrownBy(() -> userService.changeOwnPassword(user.id(), GOOD, "short1!"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("password policy");
        assertThatThrownBy(() -> userService.changeOwnPassword(user.id(), GOOD, "password123456"))
                .as("a long but guessable password is still refused")
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> userService.changeOwnPassword(user.id(), GOOD, "pw_change_weak99!A"))
                .as("must not contain the username")
                .isInstanceOf(ApiException.class);

        assertThat(authenticates(user.username(), GOOD)).as("none of those took effect").isTrue();
    }

    /**
     * The successful path, including the flag.
     *
     * <p>{@code must_change_password} is kept for a future forced-rotation policy
     * rather than being dropped, and such a policy only works if doing the thing
     * clears the prompt — otherwise the user is asked forever. Nothing in the
     * product sets the flag today, so the fixture sets it directly.</p>
     */
    @Test
    void aValidChangeTakesEffectAndClearsTheMustChangeFlag() {
        UserDto user = activatedUser("pw_change_ok");
        jdbc.update("update user set must_change_password = 1 where id = :id",
                new MapSqlParameterSource("id", user.id()));
        assertThat(mustChangePassword(user.id())).as("fixture must actually set the flag").isTrue();

        userService.changeOwnPassword(user.id(), GOOD, "SecondChoice789!Zq");

        assertThat(authenticates(user.username(), "SecondChoice789!Zq")).isTrue();
        assertThat(authenticates(user.username(), GOOD)).as("the old password stops working").isFalse();
        assertThat(mustChangePassword(user.id()))
                .as("doing the thing the flag demands must satisfy it, or the prompt outlives its own answer")
                .isFalse();

        Integer audited = jdbc.queryForObject("""
                select count(*) from audit_log
                where action = 'user.changePassword' and resource_id = :id and reason_code = 'SELF_SERVICE'
                """, new MapSqlParameterSource("id", user.id()), Integer.class);
        assertThat(audited).as("a credential change must be answerable for afterwards").isEqualTo(1);
    }

    // ── fixture ──────────────────────────────────────────────────────────

    /**
     * A user with a password, created the only way an account can get one since
     * issue 096: through real activation. The code is issued by a second, real
     * user so {@code activation_code.issued_by}'s foreign key is satisfied.
     */
    private UserDto activatedUser(String username) {
        UserDto issuer = userService.create(new CreateUserRequest(
                "issuer_" + username, "Issuer " + username,
                "issuer_" + username + "@example.com", "admin", "zh-CN", null), "system");
        UserDto target = userService.create(new CreateUserRequest(
                username, "Display " + username, username + "@example.com", "student", "zh-CN", null), "system");
        activationService.redeem(activationService.issue(target.id(), issuer.id()).code(), GOOD,
                "198.51.100." + IP_SEQ.incrementAndGet());
        return target;
    }

    private boolean mustChangePassword(String userId) {
        Boolean flag = jdbc.queryForObject("select must_change_password from user where id = :id",
                new MapSqlParameterSource("id", userId), Boolean.class);
        return Boolean.TRUE.equals(flag);
    }

    /** Checks a password against the stored hash through the same view the auth path uses. */
    private boolean authenticates(String username, String password) {
        var view = userService.findByUsernameForAuth(username).orElseThrow();
        return passwordEncoder.matches(password, view.passwordHash());
    }

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
}
