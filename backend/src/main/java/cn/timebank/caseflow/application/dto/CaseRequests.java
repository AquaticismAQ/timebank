package cn.timebank.caseflow.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Contract §4.8. `status` is never accepted here — it only moves through the state machine (action endpoints). */
public final class CaseRequests {

    private CaseRequests() {
    }

    /**
     * {@code studentId} is required when the caller is staff filing on a
     * student's behalf (e.g. a teacher raising a {@code manualCorrection});
     * a student filing their own appeal/supplement omits it and the
     * service uses the caller's own student id.
     */
    public record CreateCaseRequest(String studentId, @NotBlank String type, String subjectType, String subjectId,
                                     @NotBlank String title, String description, Integer requestedAmountCentiCoins,
                                     @NotBlank String reasonCode) {
    }

    /** `PATCH /cases/{id}` — only in `DRAFT`/`NEEDS_INFORMATION` (service-layer check, not here). */
    public record UpdateCaseRequest(String title, String description, Integer requestedAmountCentiCoins,
                                     String reasonCode, @NotNull Long version) {
    }

    /** `POST /cases/{id}/decisions` (contract §4.8). */
    public record DecideRequest(@NotBlank String decision, @NotBlank String reasonCode, String comment,
                                 @NotNull Long caseVersion) {
    }
}
