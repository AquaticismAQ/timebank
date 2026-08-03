package cn.timebank.caseflow.application.dto;

import java.time.Instant;

/** Denormalized names travel with their id (contract §2.11). */
public record CaseDto(
        String id, String type, String studentId, String studentName, String subjectType, String subjectId,
        String title, String description, Integer requestedAmountCentiCoins, String reasonCode,
        String status, String assignedReviewerId,
        Long version, Instant createdAt, Instant submittedAt, Instant updatedAt) {
}
