package cn.timebank.rules.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.cache.CacheConfig;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.rules.application.dto.RuleRequests.CreateRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.PublishRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.UpdateRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.ValidationImpactDto;
import cn.timebank.rules.application.dto.RuleVersionDto;
import cn.timebank.rules.domain.RuleStatus;
import cn.timebank.rules.domain.RuleVersion;
import cn.timebank.rules.infrastructure.RuleVersionRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
class RuleServiceImpl implements RuleService {

    private final RuleVersionRepository repository;
    private final AuditService auditService;

    RuleServiceImpl(RuleVersionRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RuleVersionDto> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public RuleVersionDto get(String id) {
        return toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public RuleVersionDto createDraft(CreateRuleRequest request, String actorId) {
        RuleVersion rule = new RuleVersion(OpaqueIds.generate("rule"),
                request.roundingMinutes(), request.minutesPerCoinUnit(), request.centiCoinsPerUnit(),
                request.annualRequiredMinutes(), request.stageRequiredMinutes(),
                request.honourBronzeMinutes(), request.honourSilverMinutes(),
                request.honourGoldMinutes(), request.honourExcellentMinutes(), actorId, Instant.now());
        repository.save(rule);

        auditService.record(new AuditEntry(actorId, "rules.manage", "rule.createDraft", "rule", rule.getId(),
                null, request, null));
        return toDto(rule);
    }

    @Override
    @Transactional
    public RuleVersionDto updateDraft(String id, UpdateRuleRequest request, String actorId) {
        RuleVersion rule = findOrThrow(id);
        if (rule.getStatus() != RuleStatus.DRAFT) {
            // Invariant 7: effective rules cannot be modified in place.
            throw ApiException.stateConflict("Only draft rules can be edited — publish a new version instead.");
        }
        ConcurrencyControl.checkVersion(rule.getVersion(), request.version());
        applyUpdates(rule, request);
        rule.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(actorId, "rules.manage", "rule.updateDraft", "rule", rule.getId(),
                null, request, null));
        return toDto(rule);
    }

    @Override
    @Transactional(readOnly = true)
    public ValidationImpactDto validate(String id) {
        RuleVersion rule = findOrThrow(id);
        List<String> issues = new ArrayList<>();
        if (rule.getHonourBronzeMinutes() >= rule.getHonourSilverMinutes()
                || rule.getHonourSilverMinutes() >= rule.getHonourGoldMinutes()
                || rule.getHonourGoldMinutes() >= rule.getHonourExcellentMinutes()) {
            issues.add("Honour tiers must be strictly increasing: bronze < silver < gold < excellent.");
        }
        if (rule.getRoundingMinutes() <= 0 || rule.getMinutesPerCoinUnit() <= 0 || rule.getCentiCoinsPerUnit() <= 0) {
            issues.add("Rounding and conversion parameters must be positive.");
        }
        if (rule.getStageRequiredMinutes() < rule.getAnnualRequiredMinutes()) {
            issues.add("Stage requirement should not be less than a single year's annual requirement.");
        }
        // affectedRecordCount: always 0 until B3's service_record table exists to
        // count against — an honest placeholder, not a broken query (Docs/implementation/00-Status.md).
        return new ValidationImpactDto(issues.isEmpty(), issues, 0);
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.DICTIONARIES, allEntries = true)
    public RuleVersionDto publish(String id, PublishRuleRequest request, String actorId) {
        RuleVersion rule = findOrThrow(id);
        if (rule.getStatus() != RuleStatus.DRAFT) {
            throw ApiException.stateConflict("Only a draft rule can be published.");
        }
        ConcurrencyControl.checkVersion(rule.getVersion(), request.version());
        ValidationImpactDto validation = validate(id);
        if (!validation.valid()) {
            throw ApiException.validationFailed("Rule fails validation and cannot be published.", List.of());
        }

        Instant now = Instant.now();
        // Close whichever rule is currently effective — the two windows must
        // never overlap, and this is the one write allowed against an
        // already-published row (closing its window, not editing its terms).
        repository.findByStatusAndEffectiveFromLessThanEqual(RuleStatus.PUBLISHED, request.effectiveFrom()).stream()
                .filter(r -> r.isEffectiveAt(request.effectiveFrom()))
                .max(Comparator.comparing(RuleVersion::getEffectiveFrom))
                .ifPresent(current -> current.setEffectiveTo(request.effectiveFrom()));

        rule.setStatus(RuleStatus.PUBLISHED);
        rule.setEffectiveFrom(request.effectiveFrom());
        rule.setPublishedAt(now);
        rule.setUpdatedAt(now);

        auditService.record(new AuditEntry(actorId, "rules.publish", "rule.publish", "rule", rule.getId(),
                null, request, null));
        return toDto(rule);
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.DICTIONARIES, allEntries = true)
    public void archive(String id, String actorId) {
        RuleVersion rule = findOrThrow(id);
        rule.setStatus(RuleStatus.ARCHIVED);
        rule.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "rules.manage", "rule.archive", "rule", rule.getId(),
                null, null, null));
    }

    private void applyUpdates(RuleVersion rule, UpdateRuleRequest request) {
        // RuleVersion's fields are package-private-set via a constructor only;
        // draft edits go through a dedicated setter block here since drafts are
        // the one state where field-level replacement is legitimate.
        if (request.roundingMinutes() != null || request.minutesPerCoinUnit() != null || request.centiCoinsPerUnit() != null
                || request.annualRequiredMinutes() != null || request.stageRequiredMinutes() != null
                || request.honourBronzeMinutes() != null || request.honourSilverMinutes() != null
                || request.honourGoldMinutes() != null || request.honourExcellentMinutes() != null) {
            rule.applyDraftUpdate(request.roundingMinutes(), request.minutesPerCoinUnit(), request.centiCoinsPerUnit(),
                    request.annualRequiredMinutes(), request.stageRequiredMinutes(),
                    request.honourBronzeMinutes(), request.honourSilverMinutes(),
                    request.honourGoldMinutes(), request.honourExcellentMinutes());
        }
    }

    private RuleVersion findOrThrow(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Rule not found."));
    }

    private RuleVersionDto toDto(RuleVersion r) {
        return new RuleVersionDto(r.getId(), r.getStatus().name(),
                r.getRoundingMinutes(), r.getMinutesPerCoinUnit(), r.getCentiCoinsPerUnit(),
                r.getAnnualRequiredMinutes(), r.getStageRequiredMinutes(),
                r.getHonourBronzeMinutes(), r.getHonourSilverMinutes(), r.getHonourGoldMinutes(), r.getHonourExcellentMinutes(),
                r.getEffectiveFrom(), r.getEffectiveTo(), r.getVersion(), r.getCreatedAt(), r.getUpdatedAt(), r.getPublishedAt());
    }
}
