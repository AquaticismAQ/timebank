package cn.timebank.enrollment.application.dto;

import java.time.Instant;

public record EnrollmentDto(String id, String projectId, String projectTitle, String studentId, String studentName,
                             String status, Instant appliedAt, Instant confirmedAt, Instant completedAt,
                             Instant cancelledAt, String note, Long version) {
}
