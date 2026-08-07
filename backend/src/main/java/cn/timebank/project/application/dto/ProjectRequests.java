package cn.timebank.project.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/** Contract §13.4 — the writable subset of §3.1's core fields; `status`/`enrolledCount`/`myApplicationStatus` are never accepted here. */
public final class ProjectRequests {

    private ProjectRequests() {
    }

    /** {@code ownerId}: admin-only override (silently forced to the caller for anyone else — Docs/clarification/02-PorjectOrganization.md §3). */
    public record CreateProjectRequest(
            @NotBlank String title, String summary, String description,
            @NotBlank String categoryId, String organizationId, String ownerId,
            @NotNull Instant startAt, @NotNull Instant endAt, String location,
            @Positive int capacity, @Positive int recognizedMinutes, List<String> eligibleGradeIds,
            String evidenceRequirements, @NotNull Instant registrationOpenAt, @NotNull Instant registrationCloseAt) {
    }

    /** `PATCH /projects/{id}` — never `status`; action endpoints own that (B4.2). {@code ownerId}: admin-only, ignored (not applied) from any other caller. */
    public record UpdateProjectRequest(
            String title, String summary, String description,
            String categoryId, String organizationId, String ownerId,
            Instant startAt, Instant endAt, String location,
            Integer capacity, Integer recognizedMinutes, List<String> eligibleGradeIds,
            String evidenceRequirements, Instant registrationOpenAt, Instant registrationCloseAt,
            @NotNull Long version) {
    }

    /** `/publish`, `/archive` — action endpoints carry only the optimistic-lock version (B4.2). */
    public record ActionRequest(@NotNull Long version) {
    }

    /** `/cancel` — the manager override (02-PorjectOrganization.md §2.1); {@code reason} is required and lands in the audit log's `reasonCode`. */
    public record CancelProjectRequest(@NotBlank String reason, @NotNull Long version) {
    }
}
