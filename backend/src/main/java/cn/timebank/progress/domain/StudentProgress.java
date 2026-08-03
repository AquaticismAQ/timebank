package cn.timebank.progress.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A stored 1:1 projection per student (B3.11), updated only from inside
 * {@code ReviewServiceImpl}'s approval transaction (invariant 5's fourth
 * effect). Each contribution it accumulates was already computed against
 * the rule version in effect at that record's own service date (B3.13) —
 * this entity only sums already-resolved amounts, so invariant 8 holds
 * without this class ever consulting "the current rule" for a past
 * record. {@code honourLevel} is the one exception: it reflects the
 * *current* rule's tier thresholds against the lifetime total, evaluated
 * at the moment of the triggering credit — a live status label, not a
 * historical fact about a specific record.
 */
@Entity
@Table(name = "student_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentProgress extends VersionedEntity {

    @Id
    @Column(name = "student_id", length = 32, nullable = false, updatable = false)
    private String studentId;

    @Setter
    @Column(name = "total_credited_minutes", nullable = false)
    private int totalCreditedMinutes;

    @Setter
    @Column(name = "total_credited_centi_coins", nullable = false)
    private int totalCreditedCentiCoins;

    @Setter
    @Column(name = "honour_level", length = 16)
    private String honourLevel;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public StudentProgress(String studentId, Instant now) {
        this.studentId = studentId;
        this.totalCreditedMinutes = 0;
        this.totalCreditedCentiCoins = 0;
        this.honourLevel = "NONE";
        this.updatedAt = now;
    }
}
