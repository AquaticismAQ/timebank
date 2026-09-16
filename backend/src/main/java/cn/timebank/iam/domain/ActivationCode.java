package cn.timebank.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single-use first-access credential (10-StudentIdentityAndOnboarding.md
 * §2.4/§2.5, table added in V27). The student redeems it by choosing their own
 * password; no password is ever generated server-side.
 *
 * <p><b>Only the SHA-256 hash is stored, and lookup is by that hash</b> — the
 * same shape as {@link PasswordResetToken}, and for the same reason: the code
 * is its own lookup key, so redeeming it needs no username. The plaintext
 * exists exactly twice — at generation, on its way to the printed slip, and in
 * the redeem request — and nowhere at rest. There is deliberately no field,
 * getter or constructor argument on this class that could hold it.</p>
 *
 * <p>Unsalted SHA-256 rather than bcrypt is a knowing trade recorded in the
 * plan's §5: a salted digest cannot be looked up by, and a salted-per-row
 * scheme would mean bcrypt-ing every outstanding row on every attempt. The
 * code's own 45 bits of entropy, not a slow KDF, is what makes it unguessable
 * (§2.5), and unlike a password it is single-use and expires in days.</p>
 *
 * <p>{@code redeemedAt} is a timestamp rather than a boolean because "when did
 * this stop being usable" is worth keeping, and because single-use is then
 * enforceable as one atomic conditional update on "is this still null" — see
 * {@code ActivationCodeRepository.markRedeemed}.</p>
 */
@Entity
@Table(name = "activation_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivationCode {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 32, nullable = false, updatable = false)
    private String userId;

    /** SHA-256 hex of the normalized 10-character code. Never the code itself. */
    @Column(name = "code_hash", length = 64, nullable = false, updatable = false)
    private String codeHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    /** §2.8's provenance line, printed on every slip: which admin issued this batch. */
    @Column(name = "issued_by", length = 32, nullable = false, updatable = false)
    private String issuedBy;

    public ActivationCode(String id, String userId, String codeHash,
                           Instant issuedAt, Instant expiresAt, String issuedBy) {
        this.id = id;
        this.userId = userId;
        this.codeHash = codeHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.issuedBy = issuedBy;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRedeemed() {
        return redeemedAt != null;
    }
}
