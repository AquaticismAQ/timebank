package cn.timebank.rules.application.dto;

import java.time.Instant;

public record RuleVersionDto(
        String id, String status,
        int roundingMinutes, int minutesPerCoinUnit, int centiCoinsPerUnit,
        int annualRequiredMinutes, int stageRequiredMinutes,
        int honourBronzeMinutes, int honourSilverMinutes, int honourGoldMinutes, int honourExcellentMinutes,
        Instant effectiveFrom, Instant effectiveTo,
        Long version, Instant createdAt, Instant updatedAt, Instant publishedAt) {
}
