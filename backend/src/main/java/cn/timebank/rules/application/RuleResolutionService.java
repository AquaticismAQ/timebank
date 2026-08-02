package cn.timebank.rules.application;

import cn.timebank.rules.application.dto.EffectiveRule;

import java.time.Instant;
import java.util.Optional;

/**
 * B2.9: "the rule version in effect at date D" — B3's crediting and
 * progress computation call this, never "the current settings" (invariant
 * 8, R7). A rule change published today must never alter what an
 * already-credited record from last month is explained by.
 */
public interface RuleResolutionService {

    Optional<EffectiveRule> resolveEffectiveAt(Instant serviceDate);
}
