package cn.timebank.iam.application;

import cn.timebank.common.error.ApiException;
import cn.timebank.iam.infrastructure.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.function.IntSupplier;

/**
 * The 8-digit login a student types (10-StudentIdentityAndOnboarding.md
 * §2.1, D-1). Generated once at import and never regenerated — not when a
 * display name is corrected, not when a slip is reissued: it is the
 * student's login, not a derived attribute.
 *
 * <p>§2.1 names three things that bite if missed, and each maps to
 * something enforced here rather than left to the caller:</p>
 *
 * <ul>
 *   <li><b>Random, not sequential.</b> A sequential username leaks
 *       enrollment order and cohort size, which is why the sequential
 *       option was rejected. {@link SecureRandom} draws uniformly across
 *       the whole range; nothing here reads the current user count.</li>
 *   <li><b>No leading zeros.</b> {@code 00482155} silently becomes
 *       {@code 482155} the moment anyone opens a list in a spreadsheet.
 *       The range starts at {@value #LOWEST}, so every draw already has
 *       eight significant digits and no padding is ever needed — and
 *       {@link #format(int)} refuses an out-of-range draw outright rather
 *       than padding it, so a future change to the draw cannot quietly
 *       reintroduce the zero.</li>
 *   <li><b>Stored as a string.</b> The value is a {@code String} from the
 *       moment it exists and is written to {@code user.username}, a
 *       {@code VARCHAR} shared with {@code teacher001}-style staff names.
 *       There is no int-typed path for it to be "optimised" onto.</li>
 * </ul>
 *
 * <p>Collision handling is mandatory, not optional (§3.2): each candidate
 * is checked against {@code uk_user_username} before it is handed back, and
 * a taken one is redrawn. The pre-check is not a substitute for the unique
 * index — two concurrent imports could still both pass it — so the caller
 * flushes the insert and lets a genuine index violation fail that row
 * loudly, rather than silently minting a duplicate login.</p>
 */
@Component
class StudentUsernameGenerator {

    /** Inclusive. The first 8-digit number, which is what makes leading zeros unrepresentable. */
    static final int LOWEST = 10_000_000;

    /** Inclusive. */
    static final int HIGHEST = 99_999_999;

    /**
     * Redraw budget. With a 9x10^7 pool and a school-sized roster the first
     * draw all but always lands; this bound exists so that an exhausted or
     * broken source fails visibly instead of spinning forever.
     */
    static final int MAX_ATTEMPTS = 50;

    private final UserRepository userRepository;
    private final IntSupplier source;

    @Autowired
    StudentUsernameGenerator(UserRepository userRepository) {
        this(userRepository, secureRandomSource());
    }

    /** Test seam: lets a test force a specific draw sequence, including a deliberate collision. */
    StudentUsernameGenerator(UserRepository userRepository, IntSupplier source) {
        this.userRepository = userRepository;
        this.source = source;
    }

    static IntSupplier secureRandomSource() {
        SecureRandom random = new SecureRandom();
        // nextInt(bound) is exclusive, hence the +1 — without it 99999999
        // could never be drawn, which is a small bias, not a correctness bug,
        // but the kind that is easier to get right than to notice later.
        return () -> LOWEST + random.nextInt(HIGHEST - LOWEST + 1);
    }

    /**
     * @return an 8-digit username not currently held by any account
     */
    String generate() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String candidate = format(source.getAsInt());
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
        throw ApiException.stateConflict(
                "Could not allocate a free student username after " + MAX_ATTEMPTS + " attempts.");
    }

    /**
     * The one place a drawn number becomes a username. Deliberately has no
     * zero-padding branch: a draw outside the 8-digit range is a bug in the
     * source, and padding it would produce exactly the {@code 00482155} that
     * §2.1 rules out.
     */
    private static String format(int drawn) {
        if (drawn < LOWEST || drawn > HIGHEST) {
            throw new IllegalStateException(
                    "Student username source produced " + drawn + ", outside " + LOWEST + "-" + HIGHEST);
        }
        return Integer.toString(drawn);
    }
}
