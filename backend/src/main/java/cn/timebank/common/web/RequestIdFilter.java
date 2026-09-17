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
     * The client address, taken from {@code getRemoteAddr()} and nothing else.
     *
     * <p>This used to read {@code X-Forwarded-For} and take the leftmost entry.
     * That entry is supplied by the <em>client</em>: Nginx is configured with
     * {@code $proxy_add_x_forwarded_for}, which <b>appends</b> the real peer to
     * whatever arrived, so a request carrying {@code X-Forwarded-For: 9.9.9.9}
     * reached the app as {@code "9.9.9.9, <real ip>"} and the leftmost value won.
     * Anyone could therefore choose the address the system attributed to them —
     * which defeated the per-IP activation limit outright (verified: 70 attempts
     * from one host with a rotating header, zero throttled) and let a failed
     * login be recorded in {@code login_log} against an innocent third party.
     *
     * <p>{@code getRemoteAddr()} is already correct here without any parsing:
     * {@code server.forward-headers-strategy: native} puts Tomcat's
     * {@code RemoteIpValve} in the chain, and it walks the header right-to-left
     * skipping only <em>trusted</em> proxies, so a forged prefix cannot survive.
     * Doing it by hand bypassed exactly that trust logic.</p>
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
