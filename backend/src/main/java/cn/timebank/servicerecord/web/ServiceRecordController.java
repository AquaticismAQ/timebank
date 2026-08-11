package cn.timebank.servicerecord.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.review.application.ReviewService;
import cn.timebank.review.application.dto.ReviewDecisionDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.RevisionDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.CreateServiceRecordRequest;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.UpdateServiceRecordRequest;
import cn.timebank.servicerecord.application.dto.TimelineEntryDto;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Contract §4.5. Owner-gated mutation endpoints (PATCH/DELETE/submit/withdraw)
 * use {@code isAuthenticated()} here — the real ownership check happens in
 * the service after loading the record (404-not-403, B1.8), the same way
 * data-scope visibility for reads is applied inside the service's
 * {@code Specification}, not as controller-level SpEL.
 */
@RestController
@RequestMapping("/api/v1/service-records")
public class ServiceRecordController {

    private static final Set<String> SORTABLE = Set.of("id", "serviceDate", "status", "createdAt");

    private final ServiceRecordService serviceRecordService;
    private final ReviewService reviewService;
    private final RequestLanguageService requestLanguageService;

    public ServiceRecordController(ServiceRecordService serviceRecordService, ReviewService reviewService,
                                    RequestLanguageService requestLanguageService) {
        this.serviceRecordService = serviceRecordService;
        this.reviewService = reviewService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('serviceRecords.read')")
    public ApiResponse<List<ServiceRecordDto>> list(
            PageQuery query, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<ServiceRecordDto> page = serviceRecordService.list(pageable, auth, query.filter("status"),
                query.filter("sourceType"), query.filter("serviceCategoryId"), query.filter("studentId"), query.search(),
                resolveLanguage(acceptLanguage, auth));
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('serviceRecords.create')")
    public ApiResponse<ServiceRecordDto> create(
            @Valid @RequestBody CreateServiceRecordRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        CookieSessionUserDetails principal = (CookieSessionUserDetails) auth.getPrincipal();
        return ApiResponse.of(serviceRecordService.createDraft(request, principal.studentId(), principal.id(),
                resolveLanguage(acceptLanguage, auth)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('serviceRecords.read')")
    public ApiResponse<ServiceRecordDto> get(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(serviceRecordService.getDetail(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ServiceRecordDto> update(
            @PathVariable String id, @Valid @RequestBody UpdateServiceRecordRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(serviceRecordService.updateDraft(id, request, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        serviceRecordService.deleteDraft(id, auth);
        return ApiResponse.of(null);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ServiceRecordDto> submit(
            @PathVariable String id, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(serviceRecordService.submit(id, auth, idempotencyKey, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ServiceRecordDto> withdraw(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(serviceRecordService.withdraw(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @GetMapping("/{id}/revisions")
    @PreAuthorize("hasAuthority('serviceRecords.read')")
    public ApiResponse<List<RevisionDto>> revisions(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(serviceRecordService.revisions(id, auth));
    }

    @GetMapping("/{id}/reviews")
    @PreAuthorize("hasAuthority('serviceRecords.read')")
    public ApiResponse<List<ReviewDecisionDto>> reviews(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        // Ownership/scope for the record itself is checked by getDetail; the review
        // history for a record the caller can't see must not be exposed either.
        serviceRecordService.getDetail(id, auth, resolveLanguage(acceptLanguage, auth));
        return ApiResponse.of(reviewService.decisionsForRecord(id));
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('serviceRecords.read')")
    public ApiResponse<List<TimelineEntryDto>> timeline(@PathVariable String id, Authentication auth) {
        List<TimelineEntryDto> entries = new ArrayList<>(serviceRecordService.timeline(id, auth));
        entries.addAll(reviewService.decisionsForRecord(id).stream()
                .map(d -> new TimelineEntryDto("review." + d.decision().toLowerCase(), d.comment(), d.reviewerId(), d.createdAt()))
                .toList());
        entries.sort(Comparator.comparing(TimelineEntryDto::occurredAt));
        return ApiResponse.of(entries);
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }
}
