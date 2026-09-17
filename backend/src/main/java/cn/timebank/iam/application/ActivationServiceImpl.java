package cn.timebank.iam.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.error.FieldErrorDetail;
import cn.timebank.iam.domain.ActivationCode;
import cn.timebank.iam.domain.PasswordPolicy;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.domain.UserStatus;
import cn.timebank.iam.infrastructure.ActivationCodeRepository;
import cn.timebank.iam.infrastructure.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 10-StudentIdentityAndOnboarding.md §3.5.
 *
 * <p><b>The plaintext code never leaves this class except as a return value.</b>
 * It is not logged, not audited, not put in an exception message, and not
 * stored — {@link #sha256Hex(String)} is the only thing the rest of the system
 * ever sees. Every {@code ApiException} raised below carries a fixed string
 * chosen here, so there is no path by which the submitted code reaches the
 * response either.</p>
 *
 * <h2>What each failure tells an attacker</h2>
 *
 * <p>§3.5 asks for distinct codes for unknown / expired / already-redeemed so
 * a child sees something useful, and simultaneously that the endpoint not leak
 * whether a code exists. Those pull against each other, so the resolution is
 * written down rather than assumed:</p>
 *
 * <ul>
 *   <li><b>Unknown</b> is generic. This is the response a guess gets, and it
 *       is the only one that costs the shared failure budget.</li>
 *   <li><b>Expired</b> and <b>already redeemed</b> are distinct, and yes, each
 *       confirms that the submitted code exists. That leak is accepted here
 *       because what it reveals is <em>by definition unusable</em>: a code in
 *       either state can no longer be redeemed, so learning it is real gains
 *       an attacker nothing they can act on. This is unlike username
 *       enumeration, where "this account exists" narrows a subsequent password
 *       attack — here there is no second factor to narrow onto.</li>
 *   <li>The counterfactual settles it. Collapsing all three into one message
 *       breaks the legitimate path §2.6 and §2.7 exist for: a student whose
 *       slip sat in a bag over a weekend would be told they mistyped it, and
 *       would retype a correct code until the per-IP limit stopped them —
 *       failures that teach nobody anything while the real answer ("ask for a
 *       reissue") stays hidden.</li>
 * </ul>
 *
 * <p>What is withheld regardless of outcome: <b>which account a code belongs
 * to</b>. No username, display name, student name or id appears in any
 * response here, so a code found on a dropped slip cannot be turned into an
 * identity lookup. All failures also share one HTTP status (422), so an
 * attacker reading status codes alone — the cheap way to run this at scale —
 * cannot separate them at all.</p>
 */
@Service
class ActivationServiceImpl implements ActivationService {

    /**
     * Distinct {@code fieldErrors[0].code} values on the {@code code} field
     * (contract §2.6), rather than new top-level error codes: the contract's
     * common-code table is a small shared vocabulary, and "which way was this
     * activation code bad" is a field-level detail, exactly what the
     * {@code fieldErrors} channel is for.
     */
    static final String ERROR_FIELD = "code";
    static final String MALFORMED = "ACTIVATION_CODE_MALFORMED";
    static final String INVALID = "ACTIVATION_CODE_INVALID";
    static final String EXPIRED = "ACTIVATION_CODE_EXPIRED";
    static final String ALREADY_USED = "ACTIVATION_CODE_ALREADY_USED";

    private final ActivationCodeRepository activationCodeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final ActivationThrottle throttle;
    private final Duration codeTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    ActivationServiceImpl(ActivationCodeRepository activationCodeRepository, UserRepository userRepository,
                           PasswordEncoder passwordEncoder, AuditService auditService, ActivationThrottle throttle,
                           @Value("${timebank.auth.activation.code-ttl-days:7}") int codeTtlDays) {
        this.activationCodeRepository = activationCodeRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.throttle = throttle;
        this.codeTtl = Duration.ofDays(codeTtlDays);
    }

    @Override
    @Transactional
    public IssuedCode issue(String userId, String issuedByUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found."));
        Instant now = Instant.now();
        Instant expiresAt = now.plus(codeTtl);

        // §2.7: the reissue exists because the old slip went missing, so the
        // old code must stop working. Flushed by the delete's own @Modifying
        // before the insert, so uk_activation_code_hash sees a clean table.
        activationCodeRepository.deleteUnredeemedFor(userId);

        String code = ActivationCodeFormat.generate(secureRandom);
        ActivationCode row = new ActivationCode(OpaqueIds.generate("act"), userId, sha256Hex(code),
                now, expiresAt, issuedByUserId);
        activationCodeRepository.save(row);

        // The audit row records that a credential was issued, to whom, by whom
        // and until when — never the code, and never its hash either (a hash
        // of a 45-bit secret is brute-forceable offline in seconds, so putting
        // it in a table read by every admin with `audit.read` would undo the
        // point of hashing it in the first place).
        auditService.record(new AuditEntry(issuedByUserId, "users.manage", "user.issueActivationCode",
                "user", userId, null, new IssuanceSummary(row.getId(), expiresAt), null));

        return new IssuedCode(userId, user.getUsername(), code, now, expiresAt);
    }

    @Override
    @Transactional
    public List<IssuedCode> issueAll(List<String> userIds, String issuedByUserId) {
        List<IssuedCode> issued = new ArrayList<>(userIds.size());
        for (String userId : userIds) {
            issued.add(issue(userId, issuedByUserId));
        }
        return issued;
    }

    @Override
    @Transactional
    public void redeem(String rawCode, String newPassword, String clientIp) {
        throttle.checkAttemptAllowed(clientIp);

        String normalized = ActivationCodeFormat.normalize(rawCode);
        if (!ActivationCodeFormat.isWellFormed(normalized)) {
            // Not charged to the failure budget: a code failing its own
            // checksum cannot match any stored hash, so counting it would let
            // an attacker empty the shared budget with arbitrary garbage and
            // lock the school out of activation (see ActivationThrottle).
            throw reject(MALFORMED, "That activation code does not look right. Please check it and try again.");
        }

        Optional<ActivationCode> found = activationCodeRepository.findByCodeHash(sha256Hex(normalized));
        if (found.isEmpty()) {
            throttle.recordGuess();
            throw reject(INVALID, "That activation code is not valid.");
        }
        ActivationCode code = found.get();

        Instant now = Instant.now();
        if (code.isRedeemed()) {
            throw reject(ALREADY_USED,
                    "That activation code has already been used. Try signing in, "
                            + "or ask your teacher for a new code.");
        }
        if (code.isExpired(now)) {
            throw reject(EXPIRED,
                    "That activation code has expired. Ask your teacher for a new one.");
        }

        User user = userRepository.findById(code.getUserId())
                .orElseThrow(() -> new IllegalStateException("Activation code references a missing user"));
        if (user.getStatus() == UserStatus.INACTIVE) {
            // An account an admin deliberately switched off must not be
            // resurrected by an old slip. Reported as the generic invalid-code
            // failure rather than a distinct one: unlike expiry, this is about
            // the *account*, and saying so would turn a found slip into a way
            // to learn something about its owner.
            throw reject(INVALID, "That activation code is not valid.");
        }

        // Single-use, decided by the database rather than by the read above:
        // two simultaneous redemptions both reach here having seen redeemedAt
        // null, and exactly one of them gets a row count of 1. The loser
        // abandons the transaction, so it cannot set a password.
        if (activationCodeRepository.markRedeemed(code.getId(), now) != 1) {
            throw reject(ALREADY_USED,
                    "That activation code has already been used. Try signing in, "
                            + "or ask your teacher for a new code.");
        }

        // §2.6: "activated means the password was set", not merely that a code
        // was redeemed — which is why all four of these happen inside the one
        // transaction that just consumed the code. A rollback anywhere below
        // puts the code back, rather than leaving a half-activated account.
        // The one password rule, same as every other place a password is set.
        // Checked here rather than only on the request record: this is the
        // transaction that spends the code, and a password refused after the
        // code was consumed would burn a student's slip for a typo.
        PasswordPolicy.validate(newPassword, user.getUsername());

        UserSummary before = new UserSummary(user);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setStatus(UserStatus.ACTIVE);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setUpdatedAt(now);

        // Self-service, so the actor is the student themselves — the same
        // convention user.changePassword uses. The activation code's row id is
        // safe provenance; the code is not, and is absent from both summaries.
        auditService.record(new AuditEntry(user.getId(), null, "user.activate", "user", user.getId(),
                before, new UserSummary(user), "ACTIVATION_CODE:" + code.getId()));
    }

    private ApiException reject(String fieldCode, String message) {
        return ApiException.validationFailed(message,
                List.of(new FieldErrorDetail(ERROR_FIELD, fieldCode, message)));
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash activation code", e);
        }
    }

    /** Compact audit summary (Design.md §13) — status only, no credential material. */
    private record UserSummary(String status, boolean hasPassword, boolean mustChangePassword) {
        UserSummary(User user) {
            this(user.getStatus().name(), user.hasUsableCredential(), user.isMustChangePassword());
        }
    }

    private record IssuanceSummary(String activationCodeId, Instant expiresAt) {
    }
}
