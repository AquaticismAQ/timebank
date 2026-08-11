package cn.timebank.review.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.review.application.ReviewService;
import cn.timebank.review.application.dto.ReviewQueueItemDto;
import cn.timebank.review.application.dto.ReviewQueueSummaryDto;
import cn.timebank.review.application.dto.ReviewRequests.AssignRequest;
import cn.timebank.review.application.dto.ReviewRequests.DecideRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Contract §4.7. Bulk review ({@code reviews.bulkDecide}) is explicitly out
 * of scope for B3 (the phase doc defers it to B5) even though the
 * permission code already exists.
 */
@RestController
public class ReviewController {

    private static final Set<String> SORTABLE = Set.of("submittedAt", "durationMinutes", "daysWaiting", "id");

    private final ReviewService reviewService;
    private final RequestLanguageService requestLanguageService;

    public ReviewController(ReviewService reviewService, RequestLanguageService requestLanguageService) {
        this.reviewService = reviewService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping("/api/v1/review-queue")
    @PreAuthorize("hasAuthority('reviews.read')")
    public ApiResponse<List<ReviewQueueItemDto>> queue(
            PageQuery query, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        // Doc 04 §3.1 D8: default sort is `daysWaiting desc` (oldest-submitted-first
        // triage order) when the caller supplies none — PageableFactory always appends
        // the `id` tiebreaker on top since "id" is in SORTABLE.
        PageQuery effectiveQuery = query.sort().isEmpty()
                ? new PageQuery(query.pageNumber(), query.pageSize(),
                        List.of(new PageQuery.SortToken("daysWaiting", true)), query.filters(), query.search())
                : query;
        Pageable pageable = PageableFactory.toPageable(effectiveQuery, SORTABLE);
        Boolean hasAnomaly = query.filter("hasAnomaly") == null ? null : Boolean.valueOf(query.filter("hasAnomaly"));
        Page<ReviewQueueItemDto> page = reviewService.queue(pageable, hasAnomaly, query.filter("gradeId"), query.search(),
                auth, resolveLanguage(acceptLanguage, auth));
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @GetMapping("/api/v1/review-queue/summary")
    @PreAuthorize("hasAuthority('reviews.read')")
    public ApiResponse<ReviewQueueSummaryDto> summary(Authentication auth) {
        return ApiResponse.of(reviewService.summary(auth));
    }

    @PostMapping("/api/v1/service-records/{id}/reviews")
    @PreAuthorize("hasAuthority('reviews.decide')")
    public ApiResponse<ServiceRecordDto> decide(
            @PathVariable String id, @Valid @RequestBody DecideRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(reviewService.decide(id, request, auth, idempotencyKey, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/service-records/{id}/assign")
    @PreAuthorize("hasAuthority('reviews.assign')")
    public ApiResponse<Void> assign(@PathVariable String id, @Valid @RequestBody AssignRequest request, Authentication auth) {
        reviewService.assign(id, request, auth);
        return ApiResponse.of(null);
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }
}
