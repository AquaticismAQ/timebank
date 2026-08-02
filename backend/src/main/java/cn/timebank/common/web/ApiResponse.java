package cn.timebank.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single-resource and list envelope shapes from contract §2.5.
 * Controllers return this type explicitly rather than a bare
 * {@code ResponseEntity<T>}, so a controller that forgets to wrap its
 * payload is visible in review (Design.md §9, §20.1 note on B0.4 exit
 * criteria).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, PageMeta page, ResponseMeta meta) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null, new ResponseMeta(RequestContext.current().requestId()));
    }

    public static <T> ApiResponse<T> ofList(T data, PageMeta page) {
        return new ApiResponse<>(data, page, new ResponseMeta(RequestContext.current().requestId()));
    }
}
