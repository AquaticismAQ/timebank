package cn.timebank.dataexchange.application.dto;

import java.time.Instant;

/** Contract §3.8/§4.13/§4.14 — the frontend polls this, matching the notifications polling shape already proven in B5.5. */
public record JobDto(String id, String kind, String resourceType, String status, String createdBy,
                      Integer totalRows, Integer validRows, int processedRows, int checkpointOffset,
                      String errorMessage, Long version, Instant createdAt, Instant updatedAt, Instant finishedAt) {
}
