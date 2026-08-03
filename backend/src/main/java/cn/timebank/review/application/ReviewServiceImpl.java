package cn.timebank.review.application;

import cn.timebank.common.idempotency.IdempotencyService;
import cn.timebank.review.application.dto.ReviewDecisionDto;
import cn.timebank.review.application.dto.ReviewQueueItemDto;
import cn.timebank.review.application.dto.ReviewQueueSummaryDto;
import cn.timebank.review.application.dto.ReviewRequests.AssignRequest;
import cn.timebank.review.application.dto.ReviewRequests.DecideRequest;
import cn.timebank.review.infrastructure.ReviewDecisionRepository;
import cn.timebank.review.infrastructure.ReviewQueueRepository;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class ReviewServiceImpl implements ReviewService {

    private static final String RESOURCE_TYPE = "serviceRecords";

    private final ReviewQueueRepository reviewQueueRepository;
    private final ReviewDecisionRepository reviewDecisionRepository;
    private final ServiceRecordService serviceRecordService;
    private final DataScopeResolver dataScopeResolver;
    private final IdempotencyService idempotencyService;
    private final ReviewDecisionTransaction reviewDecisionTransaction;

    ReviewServiceImpl(ReviewQueueRepository reviewQueueRepository, ReviewDecisionRepository reviewDecisionRepository,
                       ServiceRecordService serviceRecordService, DataScopeResolver dataScopeResolver,
                       IdempotencyService idempotencyService, ReviewDecisionTransaction reviewDecisionTransaction) {
        this.reviewQueueRepository = reviewQueueRepository;
        this.reviewDecisionRepository = reviewDecisionRepository;
        this.serviceRecordService = serviceRecordService;
        this.dataScopeResolver = dataScopeResolver;
        this.idempotencyService = idempotencyService;
        this.reviewDecisionTransaction = reviewDecisionTransaction;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewQueueItemDto> queue(Pageable pageable, Authentication auth, String resolvedLanguage) {
        ScopeDescriptor scope = resolveScope(auth);
        int limit = pageable.getPageSize();
        int offset = (int) pageable.getOffset();
        List<ReviewQueueItemDto> items = reviewQueueRepository.queryPage(scope, resolvedLanguage, limit, offset);
        long total = reviewQueueRepository.countPending(scope);
        return new PageImpl<>(items, pageable, total);
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewQueueSummaryDto summary(Authentication auth) {
        ScopeDescriptor scope = resolveScope(auth);
        return new ReviewQueueSummaryDto(reviewQueueRepository.countPending(scope),
                reviewQueueRepository.countAnomalies(scope), reviewQueueRepository.countOverdue(scope));
    }

    @Override
    public ServiceRecordDto decide(String recordId, DecideRequest request, Authentication auth, String idempotencyKey, String resolvedLanguage) {
        // Deliberately not @Transactional here — IdempotencyService.execute isn't
        // transactional itself, and the actual mutation happens in the separate
        // ReviewDecisionTransaction bean so its @Transactional applies through a
        // real Spring proxy rather than a same-class self-invocation.
        String actorId = principalId(auth);
        return idempotencyService.execute(idempotencyKey, actorId, "POST /service-records/" + recordId + "/reviews",
                request, ServiceRecordDto.class,
                () -> reviewDecisionTransaction.execute(recordId, request, actorId, auth, resolvedLanguage));
    }

    @Override
    public void assign(String recordId, AssignRequest request, Authentication auth) {
        serviceRecordService.assignReviewer(recordId, request.reviewerId(), principalId(auth));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewDecisionDto> decisionsForRecord(String recordId) {
        return reviewDecisionRepository.findByServiceRecordIdOrderByCreatedAtAsc(recordId).stream()
                .map(e -> new ReviewDecisionDto(e.getId(), e.getServiceRecordId(), e.getReviewerId(),
                        e.getDecision().name(), e.getReasonCode(), e.getComment(), e.getRecordVersionAtDecision(), e.getCreatedAt()))
                .toList();
    }

    private ScopeDescriptor resolveScope(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean grantAll = principal.roleCodes().contains("admin") || principal.roleCodes().contains("sysadmin");
        return dataScopeResolver.resolve(principal, RESOURCE_TYPE, grantAll);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
