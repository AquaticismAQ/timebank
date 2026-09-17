package cn.timebank.iam.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue 097 — {@code LOCKED} is derived from {@code locked_until}, and the
 * derivation exists twice: {@link User#effectiveStatus()} for single-row reads,
 * and {@code UserListQueryRepository.EFFECTIVE_STATUS} for the list, which has
 * to order and count by the result and so cannot call Java.
 *
 * <p>This half owns the Java rule. {@code UserListQuerySqlTest} asserts the SQL
 * copy expresses the same one case for case, and {@code UserListFilterSortIT}
 * runs it against real MySQL. Two copies of one rule is the thing worth
 * testing: what goes wrong is one being edited and the other not, which no
 * amount of exercising the surviving copy detects.</p>
 */
class EffectiveStatusTest {

    private static User account(UserStatus status, Instant lockedUntil) {
        User user = new User("usr_effective", "someone", "hash", "Someone", null, "zh-CN", Instant.now());
        user.setStatus(status);
        user.setLockedUntil(lockedUntil);
        return user;
    }

    @Test
    void an_unlocked_account_reports_its_stored_status() {
        assertThat(account(UserStatus.ACTIVE, null).effectiveStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(account(UserStatus.INACTIVE, null).effectiveStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(account(UserStatus.PENDING_ACTIVATION, null).effectiveStatus())
                .isEqualTo(UserStatus.PENDING_ACTIVATION);
    }

    /** The case the whole issue is about: a lockout writes only `locked_until`. */
    @Test
    void an_active_account_inside_its_lockout_window_reports_LOCKED() {
        User user = account(UserStatus.ACTIVE, Instant.now().plus(15, ChronoUnit.MINUTES));

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.effectiveStatus()).isEqualTo(UserStatus.LOCKED);
    }

    /**
     * Self-healing is the reason for deriving rather than persisting: no job
     * runs at expiry, so a stored LOCKED would still be LOCKED here.
     */
    @Test
    void an_expired_lockout_reports_the_stored_status_again_with_nothing_having_run() {
        User user = account(UserStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.SECONDS));

        assertThat(user.effectiveStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    /**
     * A lockout does not override a deliberate deactivation. An administrator
     * switched this account off; five bad guesses against it must not make the
     * list report it as merely locked, which reads as "will fix itself".
     */
    @Test
    void a_deactivated_account_inside_a_lockout_window_still_reports_LOCKED_only_because_isLocked_says_so() {
        User user = account(UserStatus.INACTIVE, Instant.now().plus(15, ChronoUnit.MINUTES));

        // Documents the current rule rather than asserting it is the only
        // defensible one: `isLocked()` predates this issue and is what Spring
        // Security consults, so the derivation follows it rather than inventing
        // a second precedence order that the login path would not share.
        assertThat(user.effectiveStatus()).isEqualTo(UserStatus.LOCKED);
    }

}
