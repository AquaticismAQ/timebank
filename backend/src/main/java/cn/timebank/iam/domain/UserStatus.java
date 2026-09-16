package cn.timebank.iam.domain;

/**
 * 10-StudentIdentityAndOnboarding.md §2.6 (F-6): the two activation states
 * are deliberately distinct from {@link #LOCKED}, which pairs with {@code
 * locked_until} and means a brute-force lockout. Overloading it would leave
 * an admin unable to tell a brute-forced account from one whose slip was
 * never collected, and those need different responses.
 */
public enum UserStatus {

    ACTIVE,

    INACTIVE,

    /** Brute-force lockout only — never "never activated". */
    LOCKED,

    /** Imported, no password chosen yet. Cannot authenticate: there is nothing to authenticate against. */
    PENDING_ACTIVATION,

    /** The activation window closed before the code was redeemed. Recoverable by reissuing a code (§2.7). */
    ACTIVATION_EXPIRED
}
