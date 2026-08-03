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

    public record CreateServiceRecordRequest(
            @NotBlank String sourceType, String projectId,
            String organizationId, String organizationName,
            @NotNull LocalDate serviceDate, @NotNull Instant startAt, @NotNull Instant endAt,
            String location, @NotBlank String serviceCategoryId, String description,
            String supervisorName, String supervisorContact,
            List<String> evidenceFileIds, boolean includesTravelTime) {
    }

    /** `PATCH /service-records/{id}` — only in `DRAFT`/`RETURNED` (service-layer check, not here). */
    public record UpdateServiceRecordRequest(
            String projectId, String organizationId, String organizationName,
            LocalDate serviceDate, Instant startAt, Instant endAt,
            String location, String serviceCategoryId, String description,
            String supervisorName, String supervisorContact,
            List<String> evidenceFileIds, Boolean includesTravelTime,
            @NotNull Long version) {
    }
}
