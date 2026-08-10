package cn.timebank.review.application.dto;

import java.time.Instant;
import java.time.LocalDate;

public record ReviewQueueItemDto(String id, String studentId, String studentName, int gradeLevel, String className,
                                  String sourceType, String projectTitle, String organizationName,
                                  LocalDate serviceDate, int durationMinutes,
                                  String serviceCategoryId, String serviceCategoryName,
                                  String status, Instant submittedAt, long daysWaiting, String priority,
                                  boolean hasAnomaly, String anomalyNote, String assignedReviewerId,
                                  long recordVersion) {
}
