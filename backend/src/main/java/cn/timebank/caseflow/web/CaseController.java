package cn.timebank.caseflow.web;

import cn.timebank.caseflow.application.CaseService;
import cn.timebank.caseflow.application.dto.CaseDecisionDto;
import cn.timebank.caseflow.application.dto.CaseDto;
import cn.timebank.caseflow.application.dto.CaseRequests.CreateCaseRequest;
import cn.timebank.caseflow.application.dto.CaseRequests.DecideRequest;
import cn.timebank.caseflow.application.dto.CaseRequests.UpdateCaseRequest;
import cn.timebank.caseflow.application.dto.RevisionDto;
import cn.timebank.caseflow.application.dto.TimelineEntryDto;
import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Contract §4.8. Owner-gated endpoints use `isAuthenticated()`; the real ownership check happens in the service (404-not-403, B1.8). */
@RestController
public class CaseController {

    private static final Set<String> SORTABLE = Set.of("id", "status", "createdAt", "submittedAt");

    private final CaseService caseService;
    private final RequestLanguageService requestLanguageService;

    public CaseController(CaseService caseService, RequestLanguageService requestLanguageService) {
        this.caseService = caseService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping("/api/v1/cases")
    @PreAuthorize("hasAuthority('cases.read')")
    public ApiResponse<List<CaseDto>> list(
            PageQuery query, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<CaseDto> page = caseService.list(pageable, auth, query.filter("status"), query.filter("type"),
                query.filter("gradeId"), query.filter("academicYearId"), query.search(),
                resolveLanguage(acceptLanguage, auth));
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @GetMapping("/api/v1/cases/{id}")
    @PreAuthorize("hasAuthority('cases.read')")
    public ApiResponse<CaseDto> get(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(caseService.getDetail(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/cases")
    @PreAuthorize("hasAuthority('cases.create')")
    public ApiResponse<CaseDto> create(
            @Valid @RequestBody CreateCaseRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(caseService.createDraft(request, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PatchMapping("/api/v1/cases/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CaseDto> update(
            @PathVariable String id, @Valid @RequestBody UpdateCaseRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(caseService.updateDraft(id, request, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/cases/{id}/submit")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CaseDto> submit(
            @PathVariable String id, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(caseService.submit(id, auth, idempotencyKey, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/cases/{id}/withdraw")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CaseDto> withdraw(
            @PathVariable String id,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(caseService.withdraw(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @DeleteMapping("/api/v1/cases/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        caseService.deleteDraft(id, auth);
        return ApiResponse.of(null);
    }

    @GetMapping("/api/v1/cases/{id}/timeline")
    @PreAuthorize("hasAuthority('cases.read')")
    public ApiResponse<List<TimelineEntryDto>> timeline(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(caseService.timeline(id, auth));
    }

    @GetMapping("/api/v1/cases/{id}/revisions")
    @PreAuthorize("hasAuthority('cases.read')")
    public ApiResponse<List<RevisionDto>> revisions(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(caseService.revisions(id, auth));
    }

    @PostMapping("/api/v1/cases/{id}/decisions")
    @PreAuthorize("hasAuthority('cases.decide')")
    public ApiResponse<CaseDto> decide(
            @PathVariable String id, @Valid @RequestBody DecideRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(caseService.decide(id, request, auth, idempotencyKey, resolveLanguage(acceptLanguage, auth)));
    }

    @GetMapping("/api/v1/cases/{id}/decisions")
    @PreAuthorize("hasAuthority('cases.read')")
    public ApiResponse<List<CaseDecisionDto>> decisions(@PathVariable String id, Authentication auth) {
        caseService.getDetail(id, auth, "zh-CN");
        return ApiResponse.of(caseService.decisionsForCase(id));
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }
}
