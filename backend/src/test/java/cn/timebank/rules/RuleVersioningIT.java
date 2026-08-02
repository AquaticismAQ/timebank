package cn.timebank.rules;

import cn.timebank.rules.application.RuleResolutionService;
import cn.timebank.rules.application.RuleService;
import cn.timebank.rules.application.dto.EffectiveRule;
import cn.timebank.rules.application.dto.RuleRequests.CreateRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.PublishRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.UpdateRuleRequest;
import cn.timebank.rules.application.dto.RuleVersionDto;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B2.7/B2.9's exit criteria, proven against real MySQL: publishing creates
 * immutability, and rule resolution never lets a later change reach back
 * into an earlier date (invariant 8, R7).
 */
class RuleVersioningIT extends AbstractIntegrationTest {

    @Autowired
    private RuleService ruleService;

    @Autowired
    private RuleResolutionService ruleResolutionService;

    private static final String ACTOR = "usr_test_admin";

    @Test
    void editingAPublishedRuleIsRejected() {
        RuleVersionDto draft = createDraft();
        Instant effectiveFrom = Instant.now().minus(1, ChronoUnit.DAYS);
        ruleService.publish(draft.id(), new PublishRuleRequest(effectiveFrom, draft.version()), ACTOR);

        RuleVersionDto published = ruleService.get(draft.id());
        assertThat(published.status()).isEqualTo("PUBLISHED");

        assertThatThrownBy(() -> ruleService.updateDraft(draft.id(),
                new UpdateRuleRequest(45, null, null, null, null, null, null, null, null, published.version()), ACTOR))
                .isInstanceOf(cn.timebank.common.error.ApiException.class)
                .hasMessageContaining("draft");
    }

    @Test
    void publishingANewRuleClosesThePreviousOnesWindowWithoutAlteringHistoricalResolution() {
        // First rule: effective from 60 days ago.
        RuleVersionDto firstDraft = createDraft();
        Instant firstEffectiveFrom = Instant.now().minus(60, ChronoUnit.DAYS);
        ruleService.publish(firstDraft.id(), new PublishRuleRequest(firstEffectiveFrom, firstDraft.version()), ACTOR);

        Instant oldServiceDate = Instant.now().minus(30, ChronoUnit.DAYS);
        Optional<EffectiveRule> resolvedBeforeSecondPublish = ruleResolutionService.resolveEffectiveAt(oldServiceDate);
        assertThat(resolvedBeforeSecondPublish).isPresent();
        assertThat(resolvedBeforeSecondPublish.get().ruleVersionId()).isEqualTo(firstDraft.id());

        // Second rule: effective from today, changing the rounding granularity.
        RuleVersionDto secondDraft = createDraftWithRounding(45);
        Instant secondEffectiveFrom = Instant.now();
        ruleService.publish(secondDraft.id(), new PublishRuleRequest(secondEffectiveFrom, secondDraft.version()), ACTOR);

        // The OLD service date must still resolve to the FIRST rule — a rule
        // change published today must never alter how an old record is explained.
        Optional<EffectiveRule> resolvedAfterSecondPublish = ruleResolutionService.resolveEffectiveAt(oldServiceDate);
        assertThat(resolvedAfterSecondPublish).isPresent();
        assertThat(resolvedAfterSecondPublish.get().ruleVersionId()).isEqualTo(firstDraft.id());
        assertThat(resolvedAfterSecondPublish.get().roundingMinutes()).isEqualTo(30);

        // A CURRENT service date resolves to the new rule.
        Optional<EffectiveRule> resolvedNow = ruleResolutionService.resolveEffectiveAt(Instant.now());
        assertThat(resolvedNow).isPresent();
        assertThat(resolvedNow.get().ruleVersionId()).isEqualTo(secondDraft.id());
        assertThat(resolvedNow.get().roundingMinutes()).isEqualTo(45);
    }

    @Test
    void roundingAndConversionArePureIntegerArithmetic() {
        EffectiveRule rule = new EffectiveRule("rule_x", 30, 60, 100, 4500, 18000, 1500, 4500, 9000, 18000);
        // 95 minutes rounds to 90 (nearest 30, round-half-up); 150 minutes converts to 250 centi-coins.
        assertThat(rule.roundToNearestGranularity(95)).isEqualTo(90);
        assertThat(rule.roundToNearestGranularity(100)).isEqualTo(90);
        assertThat(rule.roundToNearestGranularity(105)).isEqualTo(120);
        assertThat(rule.convertMinutesToCentiCoins(150)).isEqualTo(250);
    }

    private RuleVersionDto createDraft() {
        return createDraftWithRounding(30);
    }

    private RuleVersionDto createDraftWithRounding(int roundingMinutes) {
        return ruleService.createDraft(new CreateRuleRequest(
                roundingMinutes, 60, 100, 4500, 18000, 1500, 4500, 9000, 18000), ACTOR);
    }
}
