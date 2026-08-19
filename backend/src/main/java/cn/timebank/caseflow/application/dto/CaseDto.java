package cn.timebank.caseflow.application.dto;

import java.time.Instant;

/**
 * Denormalized names travel with their id (contract §2.11). {@code subjectLabel} is
 * Docs/optimization/04-DataViewOptimization.md §4.2 (D8)'s addition — a human-readable name for
 * {@code subjectType}/{@code subjectId} (e.g. the linked service record's {@code recordNumber}),
 * resolved server-side so the frontend never has to guess a lookup from an opaque id. Null when
 * there's no subject, the subject type isn't resolvable yet, or the underlying record predates
 * {@code recordNumber} — never falls back to displaying the raw id.
 */
public record CaseDto(
        String id, String type, String studentId, String studentName, String subjectType, String subjectId,
        String subjectLabel, String title, String description, Integer requestedAmountCentiCoins, String reasonCode,
        String status, String assignedReviewerId,
        Long version, Instant createdAt, Instant submittedAt, Instant updatedAt) {
}
