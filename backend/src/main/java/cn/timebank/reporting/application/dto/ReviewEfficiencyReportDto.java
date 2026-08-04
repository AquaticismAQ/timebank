package cn.timebank.reporting.application.dto;

import java.time.Instant;
import java.util.List;

/** `GET /reports/review-efficiency` (contract §4.13) — decision turnaround, current academic year. */
public record ReviewEfficiencyReportDto(Instant generatedAt, long totalDecisions, Double overallAvgHours,
                                         List<ReviewerFigure> byReviewer) {

    public record ReviewerFigure(String reviewerId, String reviewerName, long decisions, double avgHours) {
    }
}
