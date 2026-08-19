package cn.timebank.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A project's organization association (Docs/optimization/03-ServiceRecordFormOptimization.md
 * §4.2) — exactly one {@code HOST_SCHOOL} row (always the singleton school
 * organization, V17) plus zero or more {@code PARTNER} rows. {@code organizationId}
 * is a plain string, no JPA relationship, matching this codebase's no-cross-module-
 * JPA-relationship rule (same as {@code Project.organizationId}). Immutable once
 * created — a changed partner is a delete-and-recreate, not an update, so there is
 * no setter and no version column.
 */
@Entity
@Table(name = "project_organization")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectOrganization {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 32, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "organization_id", length = 32, nullable = false, updatable = false)
    private String organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false, updatable = false)
    private ProjectOrganizationRole role;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    public ProjectOrganization(String id, String projectId, String organizationId, ProjectOrganizationRole role, int sortOrder) {
        this.id = id;
        this.projectId = projectId;
        this.organizationId = organizationId;
        this.role = role;
        this.sortOrder = sortOrder;
    }
}
