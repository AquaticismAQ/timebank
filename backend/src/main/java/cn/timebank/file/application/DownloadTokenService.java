package cn.timebank.file.application;

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
 * B3.5's "short-lived URL" (contract §4.6) without a real object store's
 * native presigned-URL support behind it — an HMAC-signed, time-boxed
 * token the app itself validates. Not a substitute for the permission/scope
 * check in {@code FileServiceImpl.getMetadata}: the token is only ever
 * issued *after* that check passes, so its only job is bounding how long a
 * leaked link stays useful.
 */
@Component
class DownloadTokenService {

    private static final long TTL_SECONDS = 300;

    private final Mac mac;

    DownloadTokenService(@Value("${timebank.file.token-secret}") String secret) {
        try {
            this.mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize download token signer", e);
        }
    }

    String issue(String fileId) {
        long expiry = Instant.now().getEpochSecond() + TTL_SECONDS;
        String payload = fileId + ":" + expiry;
        String token = payload + ":" + sign(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /** @return the fileId if the token is valid and unexpired. */
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
        String fileId = parts[0];
        long expiry = Long.parseLong(parts[1]);
        String signature = parts[2];
        String payload = fileId + ":" + expiry;
        if (!sign(payload).equals(signature)) {
            throw ApiException.invalidRequest("Invalid download token.");
        }
        if (Instant.now().getEpochSecond() > expiry) {
            throw ApiException.invalidRequest("Download link has expired.");
        }
        return fileId;
    }

    private synchronized String sign(String payload) {
        byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(raw);
    }
}
