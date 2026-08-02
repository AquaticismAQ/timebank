package cn.timebank.security.web;

import cn.timebank.common.error.ApiErrorCode;
import cn.timebank.common.error.ApiErrorResponse;
import cn.timebank.common.web.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * B1.4 / contract §2.3: "API validates Origin/Referer against allowed
 * origins." Only checked for state-changing methods — a GET cannot mutate
 * anything, so there is nothing here for CSRF-style forgery to exploit.
 * Runs after {@link cn.timebank.common.web.RequestIdFilter} so a rejected
 * request still gets a request id in its error response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestOriginFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final List<String> allowedOrigins;
    private final ObjectMapper objectMapper;

    public RequestOriginFilter(
            @Value("${timebank.security.allowed-origins:https://tb.example.com}") String allowedOriginsCsv,
            ObjectMapper objectMapper) {
        this.allowedOrigins = List.of(allowedOriginsCsv.split(","));
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod()) || isAllowed(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = ApiErrorResponse.of(ApiErrorCode.FORBIDDEN,
                "Request origin is not allowed.", List.of(), RequestContext.current().requestId());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private boolean isAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null) {
            return allowedOrigins.contains(origin);
        }
        String referer = request.getHeader("Referer");
        if (referer != null) {
            try {
                URI uri = URI.create(referer);
                String refererOrigin = uri.getScheme() + "://" + uri.getAuthority();
                return allowedOrigins.contains(refererOrigin);
            } catch (Exception e) {
                return false;
            }
        }
        // Neither header present — not a browser cross-origin request pattern;
        // CSRF token validation (B1.4's other half) still applies regardless.
        return true;
    }
}
