package cn.timebank.caseflow.domain;

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
 * Contract §3.6 — appeal/supplement/manualCorrection. Table is {@code
 * case_record}, not {@code case} (a reserved SQL word). {@code status} has
 * no Lombok setter: {@link #applyStatus} is package-private so only {@link
 * CaseStateMachine#transition} — the single entry point — can change it.
 */
@Entity
@Table(name = "case_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Case extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false, updatable = false)
    private CaseType type;

    @Column(name = "student_id", length = 32, nullable = false, updatable = false)
    private String studentId;

    @Setter
    @Column(name = "grade_id", length = 32, nullable = false)
    private String gradeId;

    @Setter
    @Column(name = "class_id", length = 32, nullable = false)
    private String classId;

    @Setter
    @Column(name = "subject_type", length = 32)
    private String subjectType;

    @Setter
    @Column(name = "subject_id", length = 32)
    private String subjectId;

    @Setter
    @Column(name = "title", nullable = false)
    private String title;

    @Setter
    @Column(name = "description", length = 4000)
    private String description;

    /** Manual-correction only; signed centi-coins delta the resolution applies via {@code AccountService.adjust}. */
    @Setter
    @Column(name = "requested_amount_centi_coins")
    private Integer requestedAmountCentiCoins;

    @Setter
    @Column(name = "reason_code", nullable = false)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private CaseStatus status;

    @Setter
    @Column(name = "assigned_reviewer_id")
    private String assignedReviewerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Case(String id, CaseType type, String studentId, String gradeId, String classId, String title,
                String reasonCode, Instant now) {
        this.id = id;
        this.type = type;
        this.studentId = studentId;
        this.gradeId = gradeId;
        this.classId = classId;
        this.title = title;
        this.reasonCode = reasonCode;
        this.status = CaseStatus.DRAFT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Package-private: only {@link CaseStateMachine#transition} may call this. */
    void applyStatus(CaseStatus status) {
        this.status = status;
    }
}
