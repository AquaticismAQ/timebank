package cn.timebank.servicerecord.application.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Denormalized names travel with their id (contract §2.11), resolved once per read by the service, never via lazy entity navigation.
 * {@code recordNumber}/{@code travelTimeReason}/{@code supervisorKind}/{@code hostOrganizationName}/{@code partnerOrganizationNames}
 * are issue-03 additions — null/empty for historical records that pre-date them (Docs/optimization/03-ServiceRecordFormOptimization.md §5).
 * {@code gradeLevel}/{@code className}/{@code reviewerName} are Docs/optimization/04-DataViewOptimization.md §3.2's addition, so the
 * audit detail workbench doesn't have to merge this DTO with the review-queue-only {@code ReviewQueueItemDto} to show them —
 * {@code reviewedAt} was considered and deliberately left out: there's no single "last reviewed" column on the record itself, and the
 * real, multi-event review history belongs on the {@code /timeline} endpoint's review-history tab, not flattened onto this DTO.
 */
public record ServiceRecordDto(
        String id, String studentId, String studentName,
        String projectId, String projectTitle, String sourceType,
        LocalDate serviceDate, Instant startAt, Instant endAt, int durationMinutes,
        String location, String organizationId, String organizationName,
        String serviceCategoryId, String serviceCategoryName,
        String description, String supervisorKind, String supervisorName, String supervisorContact,
        List<String> evidenceFileIds, boolean includesTravelTime, String travelTimeReason,
        String recordNumber, String hostOrganizationName, List<String> partnerOrganizationNames,
        String status, int currentRevision, String assignedReviewerId, String reviewerName,
        boolean hasAnomaly, String anomalyNote, String reviewSummary,
        Integer creditedUnits, String accountTransactionId,
        int gradeLevel, String className,
        Long version, Instant createdAt, Instant submittedAt, Instant updatedAt) {
}
