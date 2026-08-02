package cn.timebank.rules.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class RuleRequests {

    private RuleRequests() {
    }

    public record CreateRuleRequest(
            @Positive int roundingMinutes, @Positive int minutesPerCoinUnit, @Positive int centiCoinsPerUnit,
            @Positive int annualRequiredMinutes, @Positive int stageRequiredMinutes,
            @Positive int honourBronzeMinutes, @Positive int honourSilverMinutes,
            @Positive int honourGoldMinutes, @Positive int honourExcellentMinutes) {
    }

    /** `PATCH /rules/{id}` — draft only; a published rule rejects this (invariant 7). */
    public record UpdateRuleRequest(
            Integer roundingMinutes, Integer minutesPerCoinUnit, Integer centiCoinsPerUnit,
            Integer annualRequiredMinutes, Integer stageRequiredMinutes,
            Integer honourBronzeMinutes, Integer honourSilverMinutes,
            Integer honourGoldMinutes, Integer honourExcellentMinutes,
            @NotNull Long version) {
    }

    public record PublishRuleRequest(@NotNull java.time.Instant effectiveFrom, @NotNull Long version) {
    }

    public record ValidationImpactDto(boolean valid, java.util.List<String> issues, long affectedRecordCount) {
    }
}
