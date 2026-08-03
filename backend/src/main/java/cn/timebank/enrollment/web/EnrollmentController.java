package cn.timebank.enrollment.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.enrollment.application.EnrollmentService;
import cn.timebank.enrollment.application.dto.EnrollmentDto;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.BulkActionRequest;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.BulkActionResult;
import cn.timebank.enrollment.application.dto.EnrollmentRequests.RegisterRequest;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Contract §4.4. Internal module is `enrollment`; REST path stays `/applications`. */
@RestController
public class EnrollmentController {

    private static final Set<String> SORTABLE = Set.of("id", "appliedAt", "status");

    private final EnrollmentService enrollmentService;
    private final RequestLanguageService requestLanguageService;

    public EnrollmentController(EnrollmentService enrollmentService, RequestLanguageService requestLanguageService) {
        this.enrollmentService = enrollmentService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping("/api/v1/applications")
    @PreAuthorize("hasAuthority('applications.read')")
    public ApiResponse<List<EnrollmentDto>> list(
            PageQuery query, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<EnrollmentDto> page = enrollmentService.list(pageable, auth, resolveLanguage(acceptLanguage, auth));
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @GetMapping("/api/v1/applications/{id}")
    @PreAuthorize("hasAuthority('applications.read')")
    public ApiResponse<EnrollmentDto> get(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(enrollmentService.getDetail(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/projects/{projectId}/applications")
    @PreAuthorize("hasAuthority('applications.create')")
    public ApiResponse<EnrollmentDto> register(
            @PathVariable String projectId, @Valid @RequestBody(required = false) RegisterRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        RegisterRequest body = request != null ? request : new RegisterRequest(null);
        return ApiResponse.of(enrollmentService.register(projectId, body, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/applications/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<EnrollmentDto> cancel(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(enrollmentService.cancel(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/applications/{id}/confirm")
    @PreAuthorize("hasAuthority('applications.manage')")
    public ApiResponse<EnrollmentDto> confirm(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(enrollmentService.confirm(id, principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/applications/{id}/reject")
    @PreAuthorize("hasAuthority('applications.manage')")
    public ApiResponse<EnrollmentDto> reject(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(enrollmentService.reject(id, principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/applications/bulk-actions")
    @PreAuthorize("hasAuthority('applications.manage')")
    public ApiResponse<List<BulkActionResult>> bulkActions(
            @Valid @RequestBody BulkActionRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(enrollmentService.bulkActions(request, principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
