package cn.timebank.common.concurrency;

import cn.timebank.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Fail-fast version check (contract §2.8): compares the client's declared
 * version — from an {@code If-Match} header or a {@code version} body
 * field — against the entity's current version *before* mutating it, so a
 * stale write is rejected with a clear "current version is N" message
 * instead of surfacing as a raw Hibernate optimistic-lock exception at
 * flush time. JPA's {@code @Version} (see {@link VersionedEntity}) remains
 * the enforcement of last resort for any call site that skips this check.
 */
public final class ConcurrencyControl {

    private ConcurrencyControl() {
    }

    public static void checkVersion(Long currentVersion, Long clientVersion) {
        if (clientVersion == null) {
            throw ApiException.invalidRequest("A version is required for this update.");
        }
        if (!clientVersion.equals(currentVersion)) {
            throw ApiException.stateConflict(
                    "This resource was changed by someone else. Current version is " + currentVersion + ".");
        }
    }

    /** Reads {@code If-Match}, tolerating the quoted-etag form (e.g. {@code "3"}). */
    public static Long readIfMatch(HttpServletRequest request) {
        String header = request.getHeader("If-Match");
        if (header == null || header.isBlank()) {
            return null;
        }
        String stripped = header.trim().replace("\"", "");
        try {
            return Long.parseLong(stripped);
        } catch (NumberFormatException e) {
            throw ApiException.invalidRequest("If-Match must be a numeric version.");
        }
    }
}
