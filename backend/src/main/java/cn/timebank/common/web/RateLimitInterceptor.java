package cn.timebank.common.web;

import cn.timebank.common.error.ApiException;
import cn.timebank.security.application.CookieSessionUserDetails;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B6.8/D1: "soft business limits" for expensive resource-creation
 * endpoints — evidence upload intents and export/import job creation.
 * Deliberately in-memory (Bucket4j, no distributed backend) and therefore
 * per-instance: under Design.md §17.1's two-instance topology an attacker
 * could get up to double the intended allowance by alternating instances,
 * an accepted trade-off named in the design itself ("soft rate limits are
 * per-instance"), unlike the security-critical login/reset limits, which
 * stay centralized at Nginx (B1.11) for exactly the reason this one isn't.
 *
 * Keyed by (route, principal) — an anonymous caller falls back to remote
 * address, though every limited route here already requires authentication.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private record Limit(String routeKey, Bandwidth bandwidth) {
    }

    private static final Limit UPLOAD_INTENTS = new Limit("uploadIntents",
            Bandwidth.builder().capacity(20).refillGreedy(20, Duration.ofMinutes(1)).build());
    private static final Limit EXPORTS = new Limit("exports",
            Bandwidth.builder().capacity(5).refillGreedy(5, Duration.ofMinutes(1)).build());
    private static final Limit IMPORTS = new Limit("imports",
            Bandwidth.builder().capacity(5).refillGreedy(5, Duration.ofMinutes(1)).build());

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Limit limit = limitFor(request);
        if (limit == null) {
            return true;
        }
        String key = limit.routeKey() + ":" + principalId();
        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder().addLimit(limit.bandwidth()).build());
        if (!bucket.tryConsume(1)) {
            throw ApiException.rateLimited("Too many requests for this action — please slow down and try again shortly.");
        }
        return true;
    }

    private Limit limitFor(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String uri = request.getRequestURI();
        if (uri.equals("/api/v1/files/upload-intents")) return UPLOAD_INTENTS;
        if (uri.equals("/api/v1/exports")) return EXPORTS;
        if (uri.equals("/api/v1/imports")) return IMPORTS;
        return null;
    }

    private String principalId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails principal) {
            return principal.id();
        }
        return "anonymous";
    }
}
