package cn.timebank.proposal.domain;

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
import java.util.List;

/**
 * Contract §3.2 — this pass uses the contract's field list as authoritative,
 * not the frontend mock's heavily divergent, never-exercised `EventProposal`
 * type (`Docs/implementation/00-Status.md` §16). {@code status} has no
 * Lombok setter: {@link #applyStatus} is package-private so only {@link
 * ProposalStateMachine#transition} — the single entry point — can change it.
 */
@Entity
@Table(name = "event_proposal")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventProposal extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "proposer_student_id", length = 32, nullable = false, updatable = false)
    private String proposerStudentId;

    @Setter
    @Column(name = "grade_id", length = 32, nullable = false)
    private String gradeId;

    @Setter
    @Column(name = "class_id", length = 32, nullable = false)
    private String classId;

    @Setter
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "co_organizer_student_ids", columnDefinition = "json")
    private List<String> coOrganizerStudentIds;

    @Setter
    @Column(name = "title", nullable = false)
    private String title;

    @Setter
    @Column(name = "objective", length = 1000)
    private String objective;

    @Setter
    @Column(name = "description", length = 4000)
    private String description;

    @Setter
    @Column(name = "expected_outcome", length = 1000)
    private String expectedOutcome;

    @Setter
    @Column(name = "category_id", nullable = false)
    private String categoryId;

    @Setter
    @Column(name = "planned_start_at", nullable = false)
    private Instant plannedStartAt;

    @Setter
    @Column(name = "planned_end_at", nullable = false)
    private Instant plannedEndAt;

    @Setter
    @Column(name = "location")
    private String location;

    @Setter
    @Column(name = "format", length = 64)
    private String format;

    @Setter
    @Column(name = "target_participants", length = 500)
    private String targetParticipants;

    @Setter
    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Setter
    @Column(name = "volunteer_tasks", length = 1000)
    private String volunteerTasks;

    @Setter
    @Column(name = "proposed_supervisor_id", nullable = false)
    private String proposedSupervisorId;

    @Setter
    @Column(name = "organization_id")
    private String organizationId;

    @Setter
    @Column(name = "external_contact")
    private String externalContact;

    @Setter
    @Column(name = "safety_plan", length = 2000)
    private String safetyPlan;

    @Setter
    @Column(name = "resource_needs", length = 1000)
    private String resourceNeeds;

    @Setter
    @Column(name = "recruitment_plan", length = 1000)
    private String recruitmentPlan;

    @Setter
    @Column(name = "proposed_recognized_minutes", nullable = false)
    private int proposedRecognizedMinutes;

    @Setter
    @Column(name = "evidence_plan", length = 1000)
    private String evidencePlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ProposalStatus status;

    @Setter
    @Column(name = "current_revision", nullable = false)
    private int currentRevision;

    @Setter
    @Column(name = "assigned_reviewer_id")
    private String assignedReviewerId;

    @Setter
    @Column(name = "approved_project_id")
    private String approvedProjectId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public EventProposal(String id, String proposerStudentId, String gradeId, String classId, String title,
                          String categoryId, Instant plannedStartAt, Instant plannedEndAt, int capacity,
                          String proposedSupervisorId, int proposedRecognizedMinutes, Instant now) {
        this.id = id;
        this.proposerStudentId = proposerStudentId;
        this.gradeId = gradeId;
        this.classId = classId;
        this.title = title;
        this.categoryId = categoryId;
        this.plannedStartAt = plannedStartAt;
        this.plannedEndAt = plannedEndAt;
        this.capacity = capacity;
        this.proposedSupervisorId = proposedSupervisorId;
        this.proposedRecognizedMinutes = proposedRecognizedMinutes;
        this.status = ProposalStatus.DRAFT;
        this.currentRevision = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Package-private: only {@link ProposalStateMachine#transition} may call this. */
    void applyStatus(ProposalStatus status) {
        this.status = status;
    }
}
