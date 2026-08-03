package cn.timebank.review.application.dto;

import java.time.Instant;
import java.time.LocalDate;

public record ReviewQueueItemDto(String id, String studentId, String studentName, String sourceType,
                                  LocalDate serviceDate, int durationMinutes,
                                  String serviceCategoryId, String serviceCategoryName,
                                  String status, Instant submittedAt, long daysWaiting, String priority,
                                  boolean hasAnomaly, String anomalyNote, String assignedReviewerId) {
}
