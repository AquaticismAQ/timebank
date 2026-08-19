package cn.timebank.proposal.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/** Contract §13.4 — the writable subset of §3.2's core fields; `status`/`currentRevision`/`approvedProjectId` are never accepted here. */
public final class ProposalRequests {

    private ProposalRequests() {
    }

    /**
     * {@code organizationId}/{@code newOrganizationName} are mutually
     * exclusive (service-layer check, not here — a cross-field rule doesn't
     * belong in a Bean Validation annotation): reference an existing
     * organization, or supply a new one's name for the backend to create
     * and link within the same transaction (contract §3.2, added
     * `Docs/optimization/01-i3-partner-school-terminology.md`).
     */
    public record CreateProposalRequest(
            List<String> coOrganizerStudentIds, @NotBlank String title, String objective, String description,
            String expectedOutcome, @NotBlank String categoryId, @NotNull Instant plannedStartAt,
            @NotNull Instant plannedEndAt, String location, String format, String targetParticipants,
            @Positive int capacity, String volunteerTasks, @NotBlank String proposedSupervisorId,
            String organizationId, String newOrganizationName, String externalContact, String safetyPlan,
            String resourceNeeds, String recruitmentPlan, @Positive int proposedRecognizedMinutes, String evidencePlan) {
    }

    /** `PATCH /event-proposals/{id}` — only in `DRAFT`/`CHANGES_REQUESTED` (service-layer check, not here). */
    public record UpdateProposalRequest(
            List<String> coOrganizerStudentIds, String title, String objective, String description,
            String expectedOutcome, String categoryId, Instant plannedStartAt, Instant plannedEndAt,
            String location, String format, String targetParticipants, Integer capacity, String volunteerTasks,
            String proposedSupervisorId, String organizationId, String newOrganizationName, String externalContact,
            String safetyPlan, String resourceNeeds, String recruitmentPlan, Integer proposedRecognizedMinutes,
            String evidencePlan, @NotNull Long version) {
    }

    /** `POST /event-proposals/{id}/reviews` (contract §4.3). */
    public record DecideRequest(@NotBlank String decision, @NotBlank String reasonCode, String comment,
                                 @NotNull Long proposalVersion) {
    }

    public record AssignRequest(@NotBlank String reviewerId) {
    }
}
