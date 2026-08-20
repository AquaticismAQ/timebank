package cn.timebank.proposal.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * Denormalized names travel with their id (contract §2.11). Field set is contract §3.2's, not the frontend mock's divergent shape (see `Docs/implementation/00-Status.md` §16).
 *
 * {@code gradeId}/{@code gradeLevel}/{@code className} are the proposal's own snapshotted
 * grade/class (the entity's own columns, set at creation from the proposer's grade/class at that
 * time — never a live re-lookup, matching {@code CaseDto}/{@code ReviewQueueItemDto}'s identical
 * convention) — added so admin/teacher list screens can filter and display by grade the same way
 * Audit Center already does.
 */
public record ProposalDto(
        String id, String proposerStudentId, String proposerStudentName, String gradeId, int gradeLevel,
        String className, List<String> coOrganizerStudentIds,
        List<String> coOrganizerNames,
        String title, String objective, String description, String expectedOutcome,
        String categoryId, String categoryName,
        Instant plannedStartAt, Instant plannedEndAt, String location, String format,
        String targetParticipants, int capacity, String volunteerTasks,
        String proposedSupervisorId, String proposedSupervisorName,
        String organizationId, String organizationName, String externalContact,
        String safetyPlan, String resourceNeeds, String recruitmentPlan,
        int proposedRecognizedMinutes, String evidencePlan,
        String status, int currentRevision, String assignedReviewerId, String approvedProjectId,
        Long version, Instant createdAt, Instant submittedAt, Instant updatedAt) {
}
