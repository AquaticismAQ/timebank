package cn.timebank.reporting.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * `GET /reports/review-efficiency` (contract §4.13) — decision turnaround
 * for the selected academic year (issue 093 §1; the current year when the
 * caller names none). {@code academicYearId} echoes the year actually
 * applied, so a caller can tell which period the figures describe.
 */
public record ReviewEfficiencyReportDto(Instant generatedAt, String academicYearId, long totalDecisions,
                                         Double overallAvgHours, List<ReviewerFigure> byReviewer) {

    public record ReviewerFigure(String reviewerId, String reviewerName, long decisions, double avgHours) {
    }
}
