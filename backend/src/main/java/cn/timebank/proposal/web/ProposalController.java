package cn.timebank.proposal.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.proposal.application.ProposalService;
import cn.timebank.proposal.application.dto.ProposalDto;
import cn.timebank.proposal.application.dto.ProposalQueueSummaryDto;
import cn.timebank.proposal.application.dto.ProposalRequests.AssignRequest;
import cn.timebank.proposal.application.dto.ProposalRequests.CreateProposalRequest;
import cn.timebank.proposal.application.dto.ProposalRequests.DecideRequest;
import cn.timebank.proposal.application.dto.ProposalRequests.UpdateProposalRequest;
import cn.timebank.proposal.application.dto.ProposalReviewDto;
import cn.timebank.proposal.application.dto.RevisionDto;
import cn.timebank.proposal.application.dto.TimelineEntryDto;
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

/** Contract §4.3. Owner-gated endpoints use `isAuthenticated()`; the real ownership check happens in the service (404-not-403, B1.8). Full paths on each mapping (no class-level prefix), matching `ServiceCategoryController`'s precedent — this controller also owns `/event-proposal-queue/summary`, which doesn't share the `/event-proposals` prefix. */
@RestController
public class ProposalController {

    private static final Set<String> SORTABLE = Set.of("id", "plannedStartAt", "status", "createdAt");

    private final ProposalService proposalService;
    private final RequestLanguageService requestLanguageService;

    public ProposalController(ProposalService proposalService, RequestLanguageService requestLanguageService) {
        this.proposalService = proposalService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping("/api/v1/event-proposals")
    @PreAuthorize("hasAuthority('eventProposals.read')")
    public ApiResponse<List<ProposalDto>> list(
            PageQuery query, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<ProposalDto> page = proposalService.list(pageable, auth, query.filter("status"), query.filter("categoryId"),
                query.search(), resolveLanguage(acceptLanguage, auth));
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @GetMapping("/api/v1/event-proposals/{id}")
    @PreAuthorize("hasAuthority('eventProposals.read')")
    public ApiResponse<ProposalDto> get(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(proposalService.getDetail(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/event-proposals")
    @PreAuthorize("hasAuthority('eventProposals.create')")
    public ApiResponse<ProposalDto> create(
            @Valid @RequestBody CreateProposalRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        CookieSessionUserDetails principal = (CookieSessionUserDetails) auth.getPrincipal();
        return ApiResponse.of(proposalService.createDraft(request, principal.studentId(), principal.id(),
                resolveLanguage(acceptLanguage, auth)));
    }

    @PatchMapping("/api/v1/event-proposals/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ProposalDto> update(
            @PathVariable String id, @Valid @RequestBody UpdateProposalRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(proposalService.updateDraft(id, request, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @DeleteMapping("/api/v1/event-proposals/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        proposalService.deleteDraft(id, auth);
        return ApiResponse.of(null);
    }

    @PostMapping("/api/v1/event-proposals/{id}/submit")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ProposalDto> submit(
            @PathVariable String id, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(proposalService.submit(id, auth, idempotencyKey, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/api/v1/event-proposals/{id}/withdraw")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ProposalDto> withdraw(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(proposalService.withdraw(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @GetMapping("/api/v1/event-proposals/{id}/revisions")
    @PreAuthorize("hasAuthority('eventProposals.read')")
    public ApiResponse<List<RevisionDto>> revisions(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(proposalService.revisions(id, auth));
    }

    @GetMapping("/api/v1/event-proposals/{id}/timeline")
    @PreAuthorize("hasAuthority('eventProposals.read')")
    public ApiResponse<List<TimelineEntryDto>> timeline(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(proposalService.timeline(id, auth));
    }

    @PostMapping("/api/v1/event-proposals/{id}/reviews")
    @PreAuthorize("hasAuthority('eventProposals.review')")
    public ApiResponse<ProposalDto> decide(
            @PathVariable String id, @Valid @RequestBody DecideRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(proposalService.decide(id, request, auth, idempotencyKey, resolveLanguage(acceptLanguage, auth)));
    }

    @GetMapping("/api/v1/event-proposals/{id}/reviews")
    @PreAuthorize("hasAuthority('eventProposals.read')")
    public ApiResponse<List<ProposalReviewDto>> reviews(@PathVariable String id, Authentication auth) {
        proposalService.getDetail(id, auth, "zh-CN");
        return ApiResponse.of(proposalService.decisionsForProposal(id));
    }

    @PostMapping("/api/v1/event-proposals/{id}/assign")
    @PreAuthorize("hasAuthority('eventProposals.assign')")
    public ApiResponse<Void> assign(@PathVariable String id, @Valid @RequestBody AssignRequest request, Authentication auth) {
        proposalService.assignReviewer(id, request.reviewerId(), principalId(auth));
        return ApiResponse.of(null);
    }

    @GetMapping("/api/v1/event-proposal-queue/summary")
    @PreAuthorize("hasAuthority('eventProposals.review')")
    public ApiResponse<ProposalQueueSummaryDto> queueSummary(Authentication auth) {
        return ApiResponse.of(proposalService.queueSummary(auth));
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
