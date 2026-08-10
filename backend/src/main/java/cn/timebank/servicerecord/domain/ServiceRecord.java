package cn.timebank.servicerecord.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import cn.timebank.common.persistence.StringListJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.time.LocalDate;
import java.util.List;

/**
 * Contract §3.4. A submitted, reviewed, or credited record is never
 * physically deletable (invariant 4) — only {@code DRAFT} is, and that is
 * enforced in {@code ServiceRecordServiceImpl}, not here.
 *
 * {@code status} has no Lombok setter: {@link #applyStatus} is
 * package-private so only {@link ServiceRecordStateMachine} — the single
 * entry point (B3.2) — can change it.
 *
 * {@code includesTravelTime} is Design.md Q5's disclosure flag; it is not
 * in contract §3.4's headline field list but the design doc requires the
 * schema to carry it.
 */
@Entity
@Table(name = "service_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceRecord extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "student_id", length = 32, nullable = false, updatable = false)
    private String studentId;

    @Setter
    @Column(name = "student_name", length = 100, nullable = false)
    private String studentName;

    @Setter
    @Column(name = "grade_id", length = 32, nullable = false)
    private String gradeId;

    @Setter
    @Column(name = "class_id", length = 32, nullable = false)
    private String classId;

    @Setter
    @Column(name = "project_id", length = 32)
    private String projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 16, nullable = false, updatable = false)
    private SourceType sourceType;

    @Setter
    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Setter
    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Setter
    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Setter
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Setter
    @Column(name = "location")
    private String location;

    @Setter
    @Column(name = "organization_id")
    private String organizationId;

    @Setter
    @Column(name = "organization_name")
    private String organizationName;

    @Setter
    @Column(name = "service_category_id", nullable = false)
    private String serviceCategoryId;

    @Setter
    @Column(name = "description", length = 2000)
    private String description;

    @Setter
    @Column(name = "supervisor_name")
    private String supervisorName;

    @Setter
    @Column(name = "supervisor_contact")
    private String supervisorContact;

    /** Docs/clarification/03-ServiceRecordFormOptimization.md §5.1 — null for historical (pre-issue-03) and EXTERNAL-sourced records. */
    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "supervisor_kind", length = 20)
    private SupervisorKind supervisorKind;

    /** Null for {@code EXTERNAL_MANUAL} (no real account) and for {@code PROJECT_OWNER} snapshots taken before this field existed. */
    @Setter
    @Column(name = "supervisor_subject_id", length = 32)
    private String supervisorSubjectId;

    /** Server-generated, unique, immutable once assigned (§5.3) — null for historical records that pre-date this field. */
    @Setter
    @Column(name = "record_number", length = 64)
    private String recordNumber;

    /** Required when {@link #includesTravelTime} is true (§2); never counted toward {@link #durationMinutes}. */
    @Setter
    @Column(name = "travel_time_reason", length = 500)
    private String travelTimeReason;

    /** PROJECT-sourced only — the host/partner organization snapshot at creation time, as JSON (same pattern as {@code ServiceRecordRevision.snapshotJson}); null for SELF and historical records. */
    @Setter
    @Column(name = "project_organizations_snapshot", columnDefinition = "json")
    private String projectOrganizationsSnapshot;

    @Setter
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "evidence_file_ids", columnDefinition = "json")
    private List<String> evidenceFileIds;

    @Setter
    @Column(name = "includes_travel_time", nullable = false)
    private boolean includesTravelTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ServiceRecordStatus status;

    @Setter
    @Column(name = "current_revision", nullable = false)
    private int currentRevision;

    @Setter
    @Column(name = "assigned_reviewer_id")
    private String assignedReviewerId;

    @Setter
    @Column(name = "has_anomaly", nullable = false)
    private boolean hasAnomaly;

    @Setter
    @Column(name = "anomaly_note", length = 512)
    private String anomalyNote;

    @Setter
    @Column(name = "review_summary", length = 512)
    private String reviewSummary;

    @Setter
    @Column(name = "credited_units")
    private Integer creditedUnits;

    @Setter
    @Column(name = "account_transaction_id")
    private String accountTransactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ServiceRecord(String id, String studentId, String studentName, String gradeId, String classId,
                          SourceType sourceType, LocalDate serviceDate, Instant startAt, Instant endAt,
                          int durationMinutes, String serviceCategoryId, Instant now) {
        this.id = id;
        this.studentId = studentId;
        this.studentName = studentName;
        this.gradeId = gradeId;
        this.classId = classId;
        this.sourceType = sourceType;
        this.serviceDate = serviceDate;
        this.startAt = startAt;
        this.endAt = endAt;
        this.durationMinutes = durationMinutes;
        this.serviceCategoryId = serviceCategoryId;
        this.status = ServiceRecordStatus.DRAFT;
        this.currentRevision = 1;
        this.hasAnomaly = false;
        this.includesTravelTime = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Package-private: only {@link ServiceRecordStateMachine#transition} may call this. */
    void applyStatus(ServiceRecordStatus status) {
        this.status = status;
    }
}
