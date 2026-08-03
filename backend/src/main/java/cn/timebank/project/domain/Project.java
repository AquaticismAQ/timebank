package cn.timebank.project.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import cn.timebank.common.persistence.StringListJsonConverter;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Contract §3.1. {@code status} has no Lombok setter: {@link #applyStatus}
 * is package-private so only {@link ProjectStateMachine} — the single entry
 * point (B4.2, copying B3.2's shape) — can change it.
 */
@Entity
@Table(name = "project")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Setter
    @Column(name = "title", nullable = false)
    private String title;

    @Setter
    @Column(name = "summary")
    private String summary;

    @Setter
    @Column(name = "description", length = 4000)
    private String description;

    @Setter
    @Column(name = "category_id", nullable = false)
    private String categoryId;

    @Setter
    @Column(name = "organization_id")
    private String organizationId;

    @Setter
    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "origin_proposal_id", updatable = false)
    private String originProposalId;

    @Setter
    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Setter
    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Setter
    @Column(name = "location")
    private String location;

    @Setter
    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Setter
    @Column(name = "recognized_minutes", nullable = false)
    private int recognizedMinutes;

    @Setter
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "eligible_grade_ids", columnDefinition = "json")
    private List<String> eligibleGradeIds;

    @Setter
    @Column(name = "evidence_requirements", length = 1000)
    private String evidenceRequirements;

    @Setter
    @Column(name = "registration_open_at", nullable = false)
    private Instant registrationOpenAt;

    @Setter
    @Column(name = "registration_close_at", nullable = false)
    private Instant registrationCloseAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ProjectStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Populated only for proposal-generated projects — the proposer + co-organizers (B4.11). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "project_student_organizer", joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "student_id")
    private Set<String> studentOrganizerIds = new HashSet<>();

    public Project(String id, String title, String categoryId, String ownerId, String originProposalId,
                    Instant startAt, Instant endAt, int capacity, int recognizedMinutes,
                    Instant registrationOpenAt, Instant registrationCloseAt, Instant now) {
        this.id = id;
        this.title = title;
        this.categoryId = categoryId;
        this.ownerId = ownerId;
        this.originProposalId = originProposalId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.capacity = capacity;
        this.recognizedMinutes = recognizedMinutes;
        this.registrationOpenAt = registrationOpenAt;
        this.registrationCloseAt = registrationCloseAt;
        this.status = ProjectStatus.DRAFT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Package-private: only {@link ProjectStateMachine#transition} may call this. */
    void applyStatus(ProjectStatus status) {
        this.status = status;
    }

    public void addStudentOrganizer(String studentId) {
        studentOrganizerIds.add(studentId);
    }
}
