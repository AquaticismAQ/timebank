package cn.timebank.review.application.dto;

public record ReviewQueueSummaryDto(long pendingCount, long anomalyCount, long overdueCount) {
}
