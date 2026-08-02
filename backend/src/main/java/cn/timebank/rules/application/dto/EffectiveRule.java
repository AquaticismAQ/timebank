package cn.timebank.rules.application.dto;

/**
 * What {@link cn.timebank.rules.application.RuleResolutionService} returns
 * — a DTO, not the {@code RuleVersion} entity, so B3/B4 can depend on it
 * without reaching into `rules.domain` (Design.md §6, B0.2's ArchUnit rule).
 * Carries its own copies of the rounding/conversion arithmetic for the
 * same reason; see {@code RuleVersion}'s identical methods for why both
 * are pure integer math (§22.1).
 */
public record EffectiveRule(
        String ruleVersionId,
        int roundingMinutes, int minutesPerCoinUnit, int centiCoinsPerUnit,
        int annualRequiredMinutes, int stageRequiredMinutes,
        int honourBronzeMinutes, int honourSilverMinutes, int honourGoldMinutes, int honourExcellentMinutes) {

    public int roundToNearestGranularity(int durationMinutes) {
        int r = roundingMinutes;
        return ((durationMinutes + r / 2) / r) * r;
    }

    public int convertMinutesToCentiCoins(int roundedMinutes) {
        return Math.toIntExact((long) roundedMinutes * centiCoinsPerUnit / minutesPerCoinUnit);
    }
}
