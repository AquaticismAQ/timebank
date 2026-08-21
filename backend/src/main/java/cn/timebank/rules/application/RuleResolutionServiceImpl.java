package cn.timebank.rules.application;

import cn.timebank.rules.application.dto.EffectiveRule;
import cn.timebank.rules.domain.RuleStatus;
import cn.timebank.rules.domain.RuleVersion;
import cn.timebank.rules.infrastructure.RuleVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

@Service
class RuleResolutionServiceImpl implements RuleResolutionService {

    private final RuleVersionRepository repository;

    RuleResolutionServiceImpl(RuleVersionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EffectiveRule> resolveEffectiveAt(Instant serviceDate) {
        // Small table (one row per published rule change, ever) — a full scan
        // filtered in Java is fine at this volume; revisit only if publication
        // frequency or table size changes materially.
        return repository.findByStatusAndEffectiveFromLessThanEqual(RuleStatus.PUBLISHED, serviceDate).stream()
                .filter(r -> r.isEffectiveAt(serviceDate))
                .max(Comparator.comparing(RuleVersion::getEffectiveFrom))
                .map(this::toEffectiveRule);
    }

    private EffectiveRule toEffectiveRule(RuleVersion r) {
        return new EffectiveRule(r.getId(), r.getRoundingMinutes(), r.getMinutesPerCoinUnit(), r.getCentiCoinsPerUnit(),
                r.getMiddleAnnualRequiredMinutes(), r.getMiddleStageRequiredMinutes(),
                r.getHighAnnualRequiredMinutes(), r.getHighStageRequiredMinutes(),
                r.getHonourBronzeMinutes(), r.getHonourSilverMinutes(), r.getHonourGoldMinutes(), r.getHonourExcellentMinutes());
    }
}
