package cn.timebank.common.error;

import cn.timebank.common.web.RequestContext;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * The single place exceptions become the contract §2.6 error envelope
 * (Design.md §9, B0.4). Nothing downstream of this handler may leak a
 * stack trace, SQL text, or a Hibernate message to the client — every
 * branch below returns a fixed, generic message for its class of failure;
 * only {@link ApiException}, which the application layer constructs
 * deliberately, carries a message chosen by our own code.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex) {
        return build(ex.code(), ex.getMessage(), ex.fieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldErrorDetail> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDetail(
                        fe.getField(),
                        fe.getCode() != null ? fe.getCode().toUpperCase() : "INVALID",
                        fe.getDefaultMessage()))
                .toList();
        return build(ApiErrorCode.VALIDATION_FAILED, "The request contains invalid fields.", fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldErrorDetail> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> new FieldErrorDetail(v.getPropertyPath().toString(), "INVALID", v.getMessage()))
                .toList();
        return build(ApiErrorCode.VALIDATION_FAILED, "The request contains invalid fields.", fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(HttpMessageNotReadableException ex) {
        return build(ApiErrorCode.INVALID_REQUEST, "The request body could not be parsed.", List.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return build(ApiErrorCode.STATE_CONFLICT,
                "This resource was changed by someone else. Reload and try again.", List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationRequired(AuthenticationException ex) {
        return build(ApiErrorCode.AUTHENTICATION_REQUIRED, "Authentication is required.", List.of());
    }

    /**
     * Contract §2.4: a 403 must not reveal whether the forbidden resource
     * exists. This handler's message is deliberately generic for that
     * reason — resource-level existence hiding is a data-scope concern
     * (B1.8) that returns 404 instead, before this handler is ever reached.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(AccessDeniedException ex) {
        return build(ApiErrorCode.FORBIDDEN, "You do not have permission to perform this action.", List.of());
    }

    /**
     * A request for a path that maps to nothing. Spring resolves an unmatched
     * URL against the static-resource handler last, so "no such endpoint"
     * arrives here as {@link NoResourceFoundException} rather than as a
     * routing failure.
     *
     * <p>Without this branch it fell through to {@link #handleUnexpected} and
     * became a <b>500</b> — wrong twice over. It told a caller the server had
     * broken when the caller had simply asked for something that does not
     * exist, and it wrote an ERROR line with a stack trace for every such
     * request. Anything scanning {@code /api/v1/auth/**} (which is
     * {@code permitAll}, so unmatched paths get this far instead of being
     * refused with a 401) could therefore manufacture unlimited ERROR noise in
     * a log retained six months for §10, burying real faults in it.</p>
     *
     * <p>Logged at DEBUG, not ERROR: a 404 is a statement about the request,
     * not about the server's health.</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex) {
        log.debug("No handler for {} {}", ex.getHttpMethod(), ex.getResourcePath());
        return build(ApiErrorCode.RESOURCE_NOT_FOUND, "No such endpoint.", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, WebRequest request) {
        // Full detail goes to the server log only, correlated by requestId (Design.md §13).
        log.error("Unhandled exception for requestId={}", RequestContext.current().requestId(), ex);
        return build(ApiErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", List.of());
    }

    private ResponseEntity<ApiErrorResponse> build(ApiErrorCode code, String message, List<FieldErrorDetail> fieldErrors) {
        String requestId = RequestContext.current().requestId();
        ApiErrorResponse body = ApiErrorResponse.of(code, message, fieldErrors, requestId);
        return ResponseEntity.status(code.httpStatus()).body(body);
    }
}
