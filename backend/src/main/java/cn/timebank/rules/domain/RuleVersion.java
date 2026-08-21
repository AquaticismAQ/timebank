package cn.timebank.rules.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One published rule set is one row — a school publishes rounding,
 * conversion, honour tiers, and hour requirements together as a single
 * consistent whole, matching how the frontend's {@code StudentProgress}
 * shape already treats them.
 *
 * §22.1: no {@code double}/{@code float} anywhere in the duration or coin
 * path — {@link #roundToNearestGranularity} and {@link #convertMinutesToCentiCoins}
 * are pure integer arithmetic, and are pure domain logic (no Spring, no
 * SQL — Design.md §6) even though nothing calls them until B3 exists to
 * credit a record.
 */
@Entity
@Table(name = "rule_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleVersion extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private RuleStatus status;

    @Column(name = "rounding_minutes", nullable = false)
    private int roundingMinutes;

    @Column(name = "minutes_per_coin_unit", nullable = false)
    private int minutesPerCoinUnit;

    @Column(name = "centi_coins_per_unit", nullable = false)
    private int centiCoinsPerUnit;

    @Column(name = "middle_annual_required_minutes", nullable = false)
    private int middleAnnualRequiredMinutes;

    @Column(name = "middle_stage_required_minutes", nullable = false)
    private int middleStageRequiredMinutes;

    @Column(name = "high_annual_required_minutes", nullable = false)
    private int highAnnualRequiredMinutes;

    @Column(name = "high_stage_required_minutes", nullable = false)
    private int highStageRequiredMinutes;

    @Column(name = "honour_bronze_minutes", nullable = false)
    private int honourBronzeMinutes;

    @Column(name = "honour_silver_minutes", nullable = false)
    private int honourSilverMinutes;

    @Column(name = "honour_gold_minutes", nullable = false)
    private int honourGoldMinutes;

    @Column(name = "honour_excellent_minutes", nullable = false)
    private int honourExcellentMinutes;

    @Setter
    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Setter
    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Setter
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    /** Design/chinese/01-TimeBankDesign.md §6: grades 6-8 are the middle-school band, 9-11 the high-school band — exactly two bands, not per-grade. */
    public static final int MIDDLE_SCHOOL_MAX_GRADE_LEVEL = 8;

    public RuleVersion(String id, int roundingMinutes, int minutesPerCoinUnit, int centiCoinsPerUnit,
                        int middleAnnualRequiredMinutes, int middleStageRequiredMinutes,
                        int highAnnualRequiredMinutes, int highStageRequiredMinutes,
                        int honourBronzeMinutes, int honourSilverMinutes, int honourGoldMinutes, int honourExcellentMinutes,
                        String createdBy, Instant now) {
        this.id = id;
        this.status = RuleStatus.DRAFT;
        this.roundingMinutes = roundingMinutes;
        this.minutesPerCoinUnit = minutesPerCoinUnit;
        this.centiCoinsPerUnit = centiCoinsPerUnit;
        this.middleAnnualRequiredMinutes = middleAnnualRequiredMinutes;
        this.middleStageRequiredMinutes = middleStageRequiredMinutes;
        this.highAnnualRequiredMinutes = highAnnualRequiredMinutes;
        this.highStageRequiredMinutes = highStageRequiredMinutes;
        this.honourBronzeMinutes = honourBronzeMinutes;
        this.honourSilverMinutes = honourSilverMinutes;
        this.honourGoldMinutes = honourGoldMinutes;
        this.honourExcellentMinutes = honourExcellentMinutes;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public int annualRequiredMinutesForGradeLevel(int gradeLevel) {
        return gradeLevel <= MIDDLE_SCHOOL_MAX_GRADE_LEVEL ? middleAnnualRequiredMinutes : highAnnualRequiredMinutes;
    }

    public int stageRequiredMinutesForGradeLevel(int gradeLevel) {
        return gradeLevel <= MIDDLE_SCHOOL_MAX_GRADE_LEVEL ? middleStageRequiredMinutes : highStageRequiredMinutes;
    }

    public boolean isEffectiveAt(Instant when) {
        if (status != RuleStatus.PUBLISHED || effectiveFrom == null) {
            return false;
        }
        boolean started = !effectiveFrom.isAfter(when);
        boolean notYetEnded = effectiveTo == null || effectiveTo.isAfter(when);
        return started && notYetEnded;
    }

    /** Round-half-up to the nearest {@code roundingMinutes}, in pure integer arithmetic. */
    public int roundToNearestGranularity(int durationMinutes) {
        int r = roundingMinutes;
        return ((durationMinutes + r / 2) / r) * r;
    }

    public int convertMinutesToCentiCoins(int roundedMinutes) {
        return Math.toIntExact((long) roundedMinutes * centiCoinsPerUnit / minutesPerCoinUnit);
    }

    /**
     * Field-level replacement for a draft's numeric parameters — the caller
     * (RuleServiceImpl) is responsible for confirming {@code status == DRAFT}
     * before calling this; invariant 7 is a service-layer check, not
     * re-enforced here, matching how every other entity in this codebase
     * trusts its application-layer caller for business-rule sequencing.
     */
    public void applyDraftUpdate(Integer roundingMinutes, Integer minutesPerCoinUnit, Integer centiCoinsPerUnit,
                                  Integer middleAnnualRequiredMinutes, Integer middleStageRequiredMinutes,
                                  Integer highAnnualRequiredMinutes, Integer highStageRequiredMinutes,
                                  Integer honourBronzeMinutes, Integer honourSilverMinutes,
                                  Integer honourGoldMinutes, Integer honourExcellentMinutes) {
        if (roundingMinutes != null) {
            this.roundingMinutes = roundingMinutes;
        }
        if (minutesPerCoinUnit != null) {
            this.minutesPerCoinUnit = minutesPerCoinUnit;
        }
        if (centiCoinsPerUnit != null) {
            this.centiCoinsPerUnit = centiCoinsPerUnit;
        }
        if (middleAnnualRequiredMinutes != null) {
            this.middleAnnualRequiredMinutes = middleAnnualRequiredMinutes;
        }
        if (middleStageRequiredMinutes != null) {
            this.middleStageRequiredMinutes = middleStageRequiredMinutes;
        }
        if (highAnnualRequiredMinutes != null) {
            this.highAnnualRequiredMinutes = highAnnualRequiredMinutes;
        }
        if (highStageRequiredMinutes != null) {
            this.highStageRequiredMinutes = highStageRequiredMinutes;
        }
        if (honourBronzeMinutes != null) {
            this.honourBronzeMinutes = honourBronzeMinutes;
        }
        if (honourSilverMinutes != null) {
            this.honourSilverMinutes = honourSilverMinutes;
        }
        if (honourGoldMinutes != null) {
            this.honourGoldMinutes = honourGoldMinutes;
        }
        if (honourExcellentMinutes != null) {
            this.honourExcellentMinutes = honourExcellentMinutes;
        }
    }
}
