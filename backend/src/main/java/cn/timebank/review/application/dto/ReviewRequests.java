package cn.timebank.review.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class ReviewRequests {

    private ReviewRequests() {
    }

    /** `POST /service-records/{id}/reviews` (contract §4.7). */
    public record DecideRequest(@NotBlank String decision, @NotBlank String reasonCode, String comment,
                                 @NotNull Long recordVersion) {
    }

    public record AssignRequest(@NotBlank String reviewerId) {
    }
}
