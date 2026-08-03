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

    public record CreateProjectRequest(
            @NotBlank String title, String summary, String description,
            @NotBlank String categoryId, String organizationId,
            @NotNull Instant startAt, @NotNull Instant endAt, String location,
            @Positive int capacity, @Positive int recognizedMinutes, List<String> eligibleGradeIds,
            String evidenceRequirements, @NotNull Instant registrationOpenAt, @NotNull Instant registrationCloseAt) {
    }

    /** `PATCH /projects/{id}` — never `status`; action endpoints own that (B4.2). */
    public record UpdateProjectRequest(
            String title, String summary, String description,
            String categoryId, String organizationId,
            Instant startAt, Instant endAt, String location,
            Integer capacity, Integer recognizedMinutes, List<String> eligibleGradeIds,
            String evidenceRequirements, Instant registrationOpenAt, Instant registrationCloseAt,
            @NotNull Long version) {
    }

    /** `/publish`, `/close`, `/archive` — action endpoints carry only the optimistic-lock version (B4.2). */
    public record ActionRequest(@NotNull Long version) {
    }
}
