package cn.timebank.enrollment.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Contract §13.4: "Empty or `note` only — the student is the principal, never a body field." */
public final class EnrollmentRequests {

    private EnrollmentRequests() {
    }

    public record RegisterRequest(String note) {
    }

    public record BulkActionRequest(@NotEmpty List<String> ids, @NotBlank String action, String reasonCode, String comment) {
    }

    public record BulkActionResult(String id, boolean success, String error) {
    }
}
