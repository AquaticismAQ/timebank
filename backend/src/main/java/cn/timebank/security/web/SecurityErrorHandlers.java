package cn.timebank.security.web;

import cn.timebank.common.error.ApiErrorCode;
import cn.timebank.common.error.ApiErrorResponse;
import cn.timebank.common.web.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Spring Security's authentication/authorization failures happen at the
 * filter level, before {@code DispatcherServlet} — {@code
 * GlobalExceptionHandler} (B0.4) never sees them, so this class writes the
 * same {@link ApiErrorResponse} shape directly. Kept as a small, isolated
 * duplication of {@code GlobalExceptionHandler}'s response-building logic
 * rather than trying to force these two architecturally separate error
 * paths through one method.
 */
@Component
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public SecurityErrorHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, ApiErrorCode.AUTHENTICATION_REQUIRED,
                "Authentication is required.");
    }

    /** Contract §2.4: never reveals whether the resource exists — the message is fixed and generic. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, ApiErrorCode.FORBIDDEN,
                "You do not have permission to perform this action.");
    }

    private void write(HttpServletResponse response, int status, ApiErrorCode code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponse body = ApiErrorResponse.of(code, message, List.of(), RequestContext.current().requestId());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
