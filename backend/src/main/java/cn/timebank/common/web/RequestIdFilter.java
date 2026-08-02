package cn.timebank.common.web;

import cn.timebank.common.OpaqueIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * First filter in the chain (B0.5). Generates or propagates a request id,
 * exposes it as the {@code X-Request-Id} response header, puts it in MDC so
 * every log line for this request correlates across both application
 * instances (Design.md §17.1), and populates {@link RequestContext} for the
 * envelope and the audit module. Always cleared, including on exception.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = OpaqueIds.generate("req");
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        RequestContext.set(requestId, clientIp(request), request.getHeader("User-Agent"));
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    /**
     * Not X-Forwarded-For-aware yet — the deployment topology (Design.md §17.1)
     * terminates TLS at Nginx in front of the app, so this will need the
     * real-IP header once Nginx is fronting the service (test-server: honoured
     * as of B0.11's proxy config). Revisit if a second untrusted proxy hop appears.
     */
    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
