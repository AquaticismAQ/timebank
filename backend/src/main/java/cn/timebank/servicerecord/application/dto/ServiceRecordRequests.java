package cn.timebank.servicerecord.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Contract §13.4: a request body is the writable subset of §3.4's core fields — status/derived fields are never accepted here. */
public final class ServiceRecordRequests {

    private ServiceRecordRequests() {
    }

    /**
     * Docs/clarification/03-ServiceRecordFormOptimization.md §3/§5.2: {@code sourceType}
     * is {@code PROJECT} or {@code SELF} for new records ({@code EXTERNAL} is historical
     * data only, rejected here — see {@code ServiceRecordServiceImpl.parseSourceTypeForCreate}).
     * For {@code PROJECT}, {@code organizationId}/{@code organizationName}/{@code location}/
     * {@code serviceCategoryId}/{@code supervisorKind}/{@code supervisorSubjectId}/
     * {@code supervisorName}/{@code supervisorContact} are all silently ignored — the
     * server derives every one of them from the project snapshot, never the client.
     * {@code supervisorKind} (one of {@code INTERNAL_USER}/{@code STUDENT}/
     * {@code EXTERNAL_MANUAL}) + {@code supervisorSubjectId} apply only to {@code SELF};
     * {@code EXTERNAL_MANUAL} uses {@code supervisorName}/{@code supervisorContact}
     * directly instead of a subject id. {@code travelTimeReason} is required when
     * {@code includesTravelTime} is true (service-layer check, not a bean validation
     * here, since it's conditionally required).
     */
    public record CreateServiceRecordRequest(
            @NotBlank String sourceType, String projectId,
            String organizationId, String organizationName,
            @NotNull LocalDate serviceDate, @NotNull Instant startAt, @NotNull Instant endAt,
            String location, String serviceCategoryId, String description,
            String supervisorKind, String supervisorSubjectId,
            String supervisorName, String supervisorContact,
            List<String> evidenceFileIds, boolean includesTravelTime, String travelTimeReason) {
    }

    /** `PATCH /service-records/{id}` — only in `DRAFT`/`RETURNED` (service-layer check, not here). Source is locked (never changes on update); the same PROJECT-derives-everything rule as create applies. */
    public record UpdateServiceRecordRequest(
            String projectId, String organizationId, String organizationName,
            LocalDate serviceDate, Instant startAt, Instant endAt,
            String location, String serviceCategoryId, String description,
            String supervisorKind, String supervisorSubjectId,
            String supervisorName, String supervisorContact,
            List<String> evidenceFileIds, Boolean includesTravelTime, String travelTimeReason,
            @NotNull Long version) {
    }
}
