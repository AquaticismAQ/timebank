package cn.timebank.dataexchange.application;

import cn.timebank.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Same HMAC-signed, time-boxed pattern as {@code file.application.
 * DownloadTokenService} (B3.5) — not reused directly since that class is
 * package-private to `file.application`; a second small copy scoped to
 * `dataexchange` is simpler than widening `file`'s internal API surface
 * for one caller. Shares the same {@code timebank.file.token-secret}
 * property so no new deployment secret is needed.
 *
 * <p>Unlike that one, this token is <b>single-use</b>: signature and expiry
 * are checked as before, and then the token is spent, so the second
 * presentation of the same link is refused
 * (`Docs/optimization/11-DataImportExportOptimization.md` §2.11, F-10 —
 * `12-DataExport.md` §3 and §8 both claimed this property before anything
 * implemented it). Single-use cannot be expressed by a signature alone: a
 * replay is byte-identical to the first use, so only state written by the
 * first use can tell them apart. That state is `export_download_redemption`
 * (V33).</p>
 */
@Component
class ExportDownloadTokenService {

    private static final long TTL_SECONDS = 300;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Mac mac;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate redemptionTransaction;

    ExportDownloadTokenService(@Value("${timebank.file.token-secret}") String secret,
                               NamedParameterJdbcTemplate jdbcTemplate,
                               PlatformTransactionManager transactionManager) {
        try {
            this.mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize export download token signer", e);
        }
        this.jdbcTemplate = jdbcTemplate;
        // REQUIRES_NEW, and not readOnly: {@link #validate} is called from
        // inside `ExportServiceImpl.download`'s read-only transaction, whose
        // connection may be prepared read-only at the JDBC level — an insert
        // joining it would fail. A new transaction also means spending the
        // token commits on its own, so a download that fails after this point
        // cannot quietly un-spend it: fail closed, and let the administrator
        // ask the job for a fresh link.
        this.redemptionTransaction = new TransactionTemplate(transactionManager);
        redemptionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The nonce is what makes "issue a new link" mean a new link.
     *
     * <p>Before single-use it did not matter that the payload was derived
     * entirely from the job id and a whole-second expiry: two links issued for
     * the same export inside the same second were byte-identical, and being
     * identical was harmless because nothing counted uses. It stops being
     * harmless the moment the first use spends the token — the "fresh" link
     * would arrive already spent, and an administrator who clicked twice in
     * quick succession would be told their brand-new link was used. Random per
     * issue, so every link is its own credential.</p>
     */
    private static final int NONCE_BYTES = 12;

    String issue(String jobId) {
        long expiry = Instant.now().getEpochSecond() + TTL_SECONDS;
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        String payload = jobId + ":" + expiry + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String token = payload + ":" + sign(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validates <i>and spends</i> the token. Order matters, and it is the
     * order an administrator's mental model expects: a link that is too old
     * is expired (they waited), a link that is intact but already spent has
     * been used (they, or their browser, fetched it once already). Reporting
     * a replay as "expired" would send them looking at the clock for
     * something that has nothing to do with the clock.
     */
    String validate(String token) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Invalid download token.");
        }
        // jobId:expiry:nonce:signature. A link issued by an older build carries
        // no nonce and is refused here as invalid — the only window in which
        // that can happen is the 300 seconds after a deployment, and re-issuing
        // costs a click.
        String[] parts = decoded.split(":");
        if (parts.length != 4) {
            throw ApiException.invalidRequest("Invalid download token.");
        }
        String jobId = parts[0];
        long expiry;
        try {
            expiry = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            // A hand-crafted token with a non-numeric expiry is a bad request,
            // not a server fault — it used to reach the caller as a 500.
            throw ApiException.invalidRequest("Invalid download token.");
        }
        String payload = jobId + ":" + expiry + ":" + parts[2];
        if (!sign(payload).equals(parts[3])) {
            throw ApiException.invalidRequest("Invalid download token.");
        }
        if (Instant.now().getEpochSecond() > expiry) {
            throw ApiException.invalidRequest("Download link has expired.");
        }
        spendOrRefuse(token, jobId);
        return jobId;
    }

    /**
     * Records the token as redeemed, or refuses it because it already was.
     *
     * <p>The insert is the check: two concurrent presentations of one token
     * race on the primary key inside InnoDB, so exactly one of them can win
     * regardless of which application instance served it. A select-then-insert
     * would leave a window wide enough to drive a second download through.</p>
     *
     * <p>Spent before the bytes are streamed, because this is the only place
     * the token is seen. A stream that dies half way therefore costs the
     * administrator a new link, which §2.11 accepts explicitly: the file
     * itself survives its retention window, so re-issuing is a click, not a
     * re-run of the export.</p>
     */
    private void spendOrRefuse(String token, String jobId) {
        try {
            redemptionTransaction.executeWithoutResult(status -> jdbcTemplate.update("""
                    insert into export_download_redemption (token_hash, job_id, redeemed_at)
                    values (:tokenHash, :jobId, :redeemedAt)
                    """, new MapSqlParameterSource()
                    .addValue("tokenHash", sha256Hex(token))
                    .addValue("jobId", jobId)
                    .addValue("redeemedAt", Instant.now())));
        } catch (DuplicateKeyException e) {
            throw ApiException.stateConflict(
                    "This download link has already been used. Each link works once — "
                            + "open the export again to get a new one.");
        }
    }

    /** Never the token itself: see V33's comment on why the stored form is a digest. */
    private static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash export download token", e);
        }
    }

    private synchronized String sign(String payload) {
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
