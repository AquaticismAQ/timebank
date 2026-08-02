package cn.timebank.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Contract §2.6: {@code {"error": {code, message, fieldErrors, requestId}}}. */
public record ApiErrorResponse(ApiErrorBody error) {

    public static ApiErrorResponse of(ApiErrorCode code, String message, List<FieldErrorDetail> fieldErrors, String requestId) {
        return new ApiErrorResponse(new ApiErrorBody(code.name(), message, fieldErrors, requestId));
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ApiErrorBody(String code, String message, List<FieldErrorDetail> fieldErrors, String requestId) {
    }
}
