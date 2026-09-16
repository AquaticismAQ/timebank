package cn.timebank.iam.application;

import java.time.Instant;
import java.util.List;

/**
 * First access by activation code (10-StudentIdentityAndOnboarding.md §2.4,
 * D-4): the student sets their own password by redeeming the single-use code
 * on their printed slip, and no password is ever generated server-side.
 *
 * <p>This is the {@code iam} module's published seam for the three slices
 * around it — §3.4 (the credential document), §3.6 (the expiry sweep) and §3.7
 * (admin password reset, which §3.7 settles as "issue a fresh activation code",
 * not a new password, so that there is one flow and one document format).</p>
 */
public interface ActivationService {

    /**
     * Issues one code and returns its plaintext — <b>the only time it is ever
     * available</b>. What is stored is the SHA-256 hash; nothing, here or
     * downstream, can recover the code from the database, so a caller that
     * loses this value must issue a new code rather than look the old one up.
     *
     * <p>Any unredeemed code the user already holds is retired (§2.7: a
     * reissue exists because the previous slip went missing, and the missing
     * one must stop working).</p>
     */
    IssuedCode issue(String userId, String issuedByUserId);

    /**
     * The batch seam §3.4 prints from: one call per class or import batch,
     * one code each, all plaintext returned once.
     *
     * <p>Deliberately applies no status filter of its own. §2.7's "regeneration
     * spares activated students" is a <em>selection</em> rule belonging to the
     * caller assembling the batch, because §3.7 legitimately issues a code for
     * an already-active account — an admin-initiated password reset is the same
     * mechanism pointed at a student who has long since activated. Baking the
     * filter in here would quietly break that.</p>
     */
    List<IssuedCode> issueAll(List<String> userIds, String issuedByUserId);

    /**
     * Redeems {@code rawCode} and sets {@code newPassword}, atomically
     * (plan §3.5). Public and unauthenticated by necessity: the account has no
     * password yet, and the code is its own lookup key, so no username is
     * involved.
     *
     * @param clientIp charged against the per-IP attempt limit
     * @throws cn.timebank.common.error.ApiException with a distinct
     *         {@code fieldErrors[0].code} per failure mode, and never anything
     *         that identifies the account a code belongs to
     */
    void redeem(String rawCode, String newPassword, String clientIp);

    /**
     * One issued slip's worth of data. {@code code} is plaintext and must not
     * be logged, audited, persisted, or put in a response other than the
     * document §3.4 streams — {@link #toString()} is overridden to redact it
     * precisely because a record's generated one would print it into the first
     * log line that ever takes an object of this type.
     *
     * @param expiresAt §2.6's window, printed on the slip as "valid before"
     */
    record IssuedCode(String userId, String username, String code, Instant issuedAt, Instant expiresAt) {

        @Override
        public String toString() {
            return "IssuedCode[userId=" + userId + ", username=" + username
                    + ", code=<redacted>, issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
        }
    }
}
