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
        int middleAnnualRequiredMinutes, int middleStageRequiredMinutes,
        int highAnnualRequiredMinutes, int highStageRequiredMinutes,
        int honourBronzeMinutes, int honourSilverMinutes, int honourGoldMinutes, int honourExcellentMinutes) {

    public int roundToNearestGranularity(int durationMinutes) {
        int r = roundingMinutes;
        return ((durationMinutes + r / 2) / r) * r;
    }

    public int convertMinutesToCentiCoins(int roundedMinutes) {
        return Math.toIntExact((long) roundedMinutes * centiCoinsPerUnit / minutesPerCoinUnit);
    }

    /**
     * Design/chinese/01-TimeBankDesign.md §6: grades 6-8 are middle school,
     * 9-11 high school — exactly two bands. Duplicates {@code
     * RuleVersion.MIDDLE_SCHOOL_MAX_GRADE_LEVEL} rather than referencing it —
     * this DTO deliberately doesn't depend on {@code rules.domain} (see the
     * class-level note above).
     */
    private static final int MIDDLE_SCHOOL_MAX_GRADE_LEVEL = 8;

    public int annualRequiredMinutesForGradeLevel(int gradeLevel) {
        return gradeLevel <= MIDDLE_SCHOOL_MAX_GRADE_LEVEL ? middleAnnualRequiredMinutes : highAnnualRequiredMinutes;
    }

    public int stageRequiredMinutesForGradeLevel(int gradeLevel) {
        return gradeLevel <= MIDDLE_SCHOOL_MAX_GRADE_LEVEL ? middleStageRequiredMinutes : highStageRequiredMinutes;
    }
}
