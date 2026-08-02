package cn.timebank.organization.application.dto;

import java.time.Instant;

/** {@code projectCount} is a server-computed aggregate (contract §2.11 rule 3), hardcoded 0 until B4's `project` table exists. */
public record OrganizationDto(
        String id, String name, String type, String contactName, String contactEmail,
        String contactPhone, String address, String status, int projectCount,
        Long version, Instant createdAt) {
}
