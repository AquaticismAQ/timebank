package cn.timebank.organization.application.dto;

import java.time.Instant;

/**
 * {@code projectCount} is a server-computed aggregate (contract §2.11 rule 3), hardcoded 0 until B4's `project` table exists.
 * {@code isSchoolOrganization} is read-only — never accepted by {@code CreateOrganizationRequest}/{@code UpdateOrganizationRequest}
 * (Docs/optimization/03-ServiceRecordFormOptimization.md §4.1).
 */
public record OrganizationDto(
        String id, String name, String type, String description, String contactName, String contactEmail,
        String contactPhone, String address, String status, boolean isSchoolOrganization, int projectCount,
        Long version, Instant createdAt) {
}
