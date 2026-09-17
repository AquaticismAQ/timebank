package cn.timebank.iam.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exhaustion warning must not scale with the attack.
 *
 * <p>The budget refills greedily, so a sustained brute force never rests at
 * zero — it drains each token as it appears. "Warn whenever tokens are zero"
 * therefore fired once per guess, which put hundreds of identical lines an hour
 * into a log retained six months for §10, during exactly the incident somebody
 * would be trying to read.</p>
 */
class ActivationThrottleTest {

    @Test
    void aSustainedAttackProducesOneWarningNotOnePerGuess() {
        ActivationThrottle throttle = new ActivationThrottle(5, 1000);

        for (int i = 0; i < 500; i++) {
            throttle.recordGuess();
        }

        // Control: the budget really is exhausted, so the warning branch was
        // reached on the great majority of those 500 calls. Without it, a
        // "1 warning" assertion would also pass on a throttle that never ran.
        assertThat(throttle.remainingFailureBudget())
                .as("the budget must actually be drained, or this test proves nothing")
                .isZero();

        assertThat(throttle.exhaustionWarningsEmitted())
                .as("500 guesses against an exhausted budget must not produce 500 log lines")
                .isEqualTo(1);
    }

    @Test
    void aBudgetWithRoomLeftWarnsAboutNothing() {
        ActivationThrottle throttle = new ActivationThrottle(100, 1000);

        for (int i = 0; i < 20; i++) {
            throttle.recordGuess();
        }

        assertThat(throttle.remainingFailureBudget()).isPositive();
        assertThat(throttle.exhaustionWarningsEmitted())
                .as("a budget that still has room is not an incident")
                .isZero();
    }
}
