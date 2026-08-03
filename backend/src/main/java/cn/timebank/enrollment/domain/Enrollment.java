package cn.timebank.enrollment.domain;

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
 * The contract's {@code Application} (internal package `enrollment` per the
 * phase doc — the REST path stays `/applications`). B4.7's capacity
 * serialization happens against {@code project}'s row lock, not this
 * entity's own — see {@code ProjectRepository.findWithLockById}'s Javadoc.
 */
@Entity
@Table(name = "enrollment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Enrollment extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 32, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "student_id", length = 32, nullable = false, updatable = false)
    private String studentId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private EnrollmentStatus status;

    @Setter
    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;

    @Setter
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Setter
    @Column(name = "completed_at")
    private Instant completedAt;

    @Setter
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public Enrollment(String id, String projectId, String studentId, EnrollmentStatus status, Instant now) {
        this.id = id;
        this.projectId = projectId;
        this.studentId = studentId;
        this.status = status;
        this.appliedAt = now;
    }
}
