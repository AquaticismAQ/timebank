package cn.timebank.project.application.dto;

import java.time.Instant;
import java.util.List;

/** Denormalized names travel with their id (contract §2.11); `myApplicationStatus` is viewer-derived, never cross-user cached. */
public record ProjectDto(
        String id, String title, String summary, String description,
        String categoryId, String categoryName, String organizationId, String organizationName,
        String ownerId, String ownerName, String originProposalId, List<String> studentOrganizerIds,
        Instant startAt, Instant endAt, String location,
        int capacity, int enrolledCount, int recognizedMinutes, List<String> eligibleGrades,
        String evidenceRequirements, Instant registrationOpenAt, Instant registrationCloseAt,
        String status, String myApplicationStatus,
        Long version, Instant createdAt, Instant updatedAt) {
}
