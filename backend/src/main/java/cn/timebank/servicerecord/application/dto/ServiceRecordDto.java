package cn.timebank.servicerecord.application.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Denormalized names travel with their id (contract §2.11) — resolved once per read by the service, never via lazy entity navigation. */
public record ServiceRecordDto(
        String id, String studentId, String studentName,
        String projectId, String sourceType,
        LocalDate serviceDate, Instant startAt, Instant endAt, int durationMinutes,
        String location, String organizationId, String organizationName,
        String serviceCategoryId, String serviceCategoryName,
        String description, String supervisorName, String supervisorContact,
        List<String> evidenceFileIds, boolean includesTravelTime,
        String status, int currentRevision, String assignedReviewerId,
        boolean hasAnomaly, String anomalyNote, String reviewSummary,
        Integer creditedUnits, String accountTransactionId,
        Long version, Instant createdAt, Instant submittedAt, Instant updatedAt) {
}
