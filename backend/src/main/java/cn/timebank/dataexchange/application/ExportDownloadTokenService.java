package cn.timebank.dataexchange.application;

import cn.timebank.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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
 */
@Component
class ExportDownloadTokenService {

    private static final long TTL_SECONDS = 300;

    private final Mac mac;

    ExportDownloadTokenService(@Value("${timebank.file.token-secret}") String secret) {
        try {
            this.mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize export download token signer", e);
        }
    }

    String issue(String jobId) {
        long expiry = Instant.now().getEpochSecond() + TTL_SECONDS;
        String payload = jobId + ":" + expiry;
        String token = payload + ":" + sign(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    String validate(String token) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Invalid download token.");
        }
        String[] parts = decoded.split(":");
        if (parts.length != 3) {
            throw ApiException.invalidRequest("Invalid download token.");
        }
        String jobId = parts[0];
        long expiry = Long.parseLong(parts[1]);
        String payload = jobId + ":" + expiry;
        if (!sign(payload).equals(parts[2])) {
            throw ApiException.invalidRequest("Invalid download token.");
        }
        if (Instant.now().getEpochSecond() > expiry) {
            throw ApiException.invalidRequest("Download link has expired.");
        }
        return jobId;
    }

    private synchronized String sign(String payload) {
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
