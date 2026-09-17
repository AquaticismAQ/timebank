package cn.timebank.iam.application;

import cn.timebank.common.error.ApiException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The two limits guarding {@code POST /auth/activate}
 * (10-StudentIdentityAndOnboarding.md §2.5, plan §3.5). They are different
 * mechanisms answering different attacks, and the second one is the point:
 *
 * <ul>
 *   <li><b>Per-IP attempt limit.</b> Consumed by every attempt, successful or
 *       not. Stops one host hammering the endpoint.</li>
 *   <li><b>Global failure budget.</b> A few hundred <em>guesses</em> per hour
 *       across the entire system, shared by everyone. §2.5 is explicit that
 *       the per-IP limit "cannot be relied on" — spread the same attack over a
 *       botnet and every individual IP stays politely under its own limit
 *       while the pool of outstanding codes is ground through anyway. The
 *       budget is what closes that case, and it does more for the distributed
 *       column of §2.5's table than adding characters to the code does.</li>
 * </ul>
 *
 * <p><b>What spends the budget, and what deliberately does not.</b> Only a
 * well-formed code that matches no row — i.e. an actual guess — is charged. A
 * failed checksum is not (it can never match a stored hash, so charging it
 * would hand an attacker a one-request-per-token way to empty the shared
 * budget and lock the whole school out of activation with garbage input), and
 * neither is an expired or already-redeemed code (those are real codes held by
 * real students on the recovery path, and a brute-forcer essentially never
 * lands on one: at ~3x10^13 codes against a few hundred outstanding, a guess
 * that hits an existing row is not how this attack goes). A successful
 * redemption spends nothing, so under normal school traffic — which succeeds —
 * the budget never depletes.</p>
 *
 * <p><b>Cost, named rather than hidden:</b> while the budget is empty, genuine
 * students are turned away too. That is chosen on purpose. Activation is
 * recoverable out of band (§2.7's reissue) and a few hours of delayed
 * activation is cheaper than a silently compromised account; a redemption is
 * also, by construction, the one outcome that never drains the budget.</p>
 *
 * <p>In-memory (Bucket4j, per instance), the same accepted trade-off
 * {@code common.web.RateLimitInterceptor} documents for Design.md §17.1's
 * two-instance topology: the effective allowance is up to double the
 * configured one. For the global budget that means "a few hundred per hour"
 * becomes "a few hundred per hour per instance", which does not change the
 * order of magnitude §2.5's table depends on.</p>
 */
@Component
class ActivationThrottle {

    private static final Logger log = LoggerFactory.getLogger(ActivationThrottle.class);

    /**
     * Ceiling on distinct IPs tracked at once. An unauthenticated endpoint has
     * no principal to key on, so a spraying attacker would otherwise grow this
     * map without bound. Clearing wholesale when full is crude — it forgives
     * everyone's per-IP history at once — but it is bounded, and the global
     * budget above is what actually covers the spraying case anyway.
     */
    private static final int MAX_TRACKED_IPS = 10_000;

    /** At most one exhaustion warning per this interval, however hard the endpoint is hit. */
    private static final int EXHAUSTION_LOG_INTERVAL_MINUTES = 10;
    private static final Duration EXHAUSTION_LOG_INTERVAL = Duration.ofMinutes(EXHAUSTION_LOG_INTERVAL_MINUTES);

    private final Bandwidth perIpBandwidth;
    private final Map<String, Bucket> perIpBuckets = new ConcurrentHashMap<>();
    private final Bucket globalFailureBudget;
    private final AtomicLong guessesCharged = new AtomicLong();
    private final AtomicLong guessesAtLastLog = new AtomicLong();
    private final AtomicLong lastExhaustionLogAtNanos = new AtomicLong();
    private final AtomicLong exhaustionWarnings = new AtomicLong();

    ActivationThrottle(
            @Value("${timebank.auth.activation.failure-budget-per-hour:300}") int failureBudgetPerHour,
            @Value("${timebank.auth.activation.attempts-per-ip-per-hour:60}") int attemptsPerIpPerHour) {
        this.perIpBandwidth = Bandwidth.builder()
                .capacity(attemptsPerIpPerHour)
                .refillGreedy(attemptsPerIpPerHour, Duration.ofHours(1))
                .build();
        this.globalFailureBudget = Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(failureBudgetPerHour)
                        .refillGreedy(failureBudgetPerHour, Duration.ofHours(1))
                        .build())
                .build();
    }

    /**
     * Called before an attempt is looked at, in this order: the shared budget
     * first (if it is empty nobody gets served, so there is no reason to spend
     * an IP's allowance finding that out), then the caller's own allowance.
     *
     * <p>Rejecting <em>before</em> the lookup is what makes the budget a
     * defence rather than a metric: letting attempts through and merely
     * counting them would leave the brute force running at full speed.</p>
     */
    void checkAttemptAllowed(String clientIp) {
        if (globalFailureBudget.getAvailableTokens() <= 0) {
            throw ApiException.rateLimited(
                    "Activation is temporarily unavailable. Please try again later, "
                            + "or ask your teacher for a new activation code.");
        }
        if (perIpBuckets.size() >= MAX_TRACKED_IPS) {
            perIpBuckets.clear();
        }
        Bucket bucket = perIpBuckets.computeIfAbsent(
                clientIp == null ? "unknown" : clientIp,
                key -> Bucket.builder().addLimit(perIpBandwidth).build());
        if (!bucket.tryConsume(1)) {
            throw ApiException.rateLimited(
                    "Too many activation attempts from this device. Please wait a while and try again.");
        }
    }

    /**
     * Charges one guess against the shared budget. Called only for a
     * well-formed code that matched no row — see the class note for why the
     * other failure modes deliberately cost nothing.
     *
     * <p>The exhaustion warning is deliberately rate limited. The budget refills
     * greedily (one token every {@code 3600/capacity} seconds), so a sustained
     * attack does not sit at zero — it drains each token as it appears, and a
     * naive "warn whenever tokens are zero" fired once per guess: roughly
     * {@value #EXHAUSTION_LOG_INTERVAL_MINUTES}-hundred lines an hour, into the
     * six-month-retained log, during precisely the incident someone would be
     * trying to read. One line per interval, carrying the number of guesses
     * charged since the last one, says strictly more while staying legible.</p>
     */
    void recordGuess() {
        globalFailureBudget.tryConsume(1);
        long charged = guessesCharged.incrementAndGet();
        if (globalFailureBudget.getAvailableTokens() > 0) {
            return;
        }
        long now = System.nanoTime();
        long last = lastExhaustionLogAtNanos.get();
        boolean due = last == 0L || now - last >= EXHAUSTION_LOG_INTERVAL.toNanos();
        if (due && lastExhaustionLogAtNanos.compareAndSet(last, now)) {
            long since = charged - guessesAtLastLog.getAndSet(charged);
            // No code, no hash, no IP, no username: this line is a signal that
            // somebody is grinding the endpoint, not a record of what they tried.
            log.warn("Activation failure budget exhausted — /auth/activate is refusing attempts "
                    + "until it refills. {} guesses charged since the last such warning. "
                    + "This is expected only under a brute-force attempt.", since);
            exhaustionWarnings.incrementAndGet();
        }
    }

    /** Test seam: how many exhaustion warnings have actually been emitted. */
    long exhaustionWarningsEmitted() {
        return exhaustionWarnings.get();
    }

    /** Test seam: how many guesses the shared budget still has. */
    long remainingFailureBudget() {
        return globalFailureBudget.getAvailableTokens();
    }
}
