package cn.timebank.common.error;

import java.util.List;

/**
 * The one exception type domain and application code throws for a
 * business-rule failure that should reach the client as contract §2.6's
 * error envelope. Deliberately a single class with factory methods rather
 * than a subclass per error code — the code carries all the behaviour that
 * differs (see {@link ApiErrorCode#httpStatus()}).
 *
 * Framework-level failures (bean validation, optimistic locking, Spring
 * Security) are mapped separately in {@link GlobalExceptionHandler}; this
 * type is for the business layer to raise directly.
 */
public class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final List<FieldErrorDetail> fieldErrors;

    private ApiException(ApiErrorCode code, String message, List<FieldErrorDetail> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = fieldErrors;
    }

    public static ApiException notFound(String message) {
        return new ApiException(ApiErrorCode.RESOURCE_NOT_FOUND, message, List.of());
    }

    public static ApiException authenticationRequired(String message) {
        return new ApiException(ApiErrorCode.AUTHENTICATION_REQUIRED, message, List.of());
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ApiErrorCode.FORBIDDEN, message, List.of());
    }

    public static ApiException stateConflict(String message) {
        return new ApiException(ApiErrorCode.STATE_CONFLICT, message, List.of());
    }

    /**
     * A state conflict the client has to tell apart from the others.
     *
     * {@code message} is English, like every message this class carries — this
     * backend has no {@code MessageSource} and does not author localized copy
     * (see {@code iam.application.SlipCopy} for why). A conflict the user is
     * meant to read therefore travels as a coded {@link FieldErrorDetail} and
     * the client renders its own wording, the same way {@code OVERLAPPING_RECORD}
     * already works. Contract §2.6 puts {@code fieldErrors} on the error envelope
     * generally, not only on {@code VALIDATION_FAILED}.
     */
    public static ApiException stateConflict(String message, List<FieldErrorDetail> fieldErrors) {
        return new ApiException(ApiErrorCode.STATE_CONFLICT, message, fieldErrors);
    }

    public static ApiException invalidRequest(String message) {
        return new ApiException(ApiErrorCode.INVALID_REQUEST, message, List.of());
    }

    public static ApiException validationFailed(String message, List<FieldErrorDetail> fieldErrors) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message, fieldErrors);
    }

    public static ApiException fileTooLarge(String message) {
        return new ApiException(ApiErrorCode.FILE_TOO_LARGE, message, List.of());
    }

    public static ApiException rateLimited(String message) {
        return new ApiException(ApiErrorCode.RATE_LIMITED, message, List.of());
    }

    public ApiErrorCode code() {
        return code;
    }

    public List<FieldErrorDetail> fieldErrors() {
        return fieldErrors;
    }
}
