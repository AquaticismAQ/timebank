package cn.timebank.reporting.application.dto;

import cn.timebank.review.application.dto.ReviewQueueItemDto;
import cn.timebank.review.application.dto.ReviewQueueSummaryDto;

import java.time.Instant;
import java.util.List;

/** `GET /dashboards/teacher` (contract §4.13) — "teacher pending and scope statistics." */
public record DashboardTeacherDto(Instant generatedAt, ReviewQueueSummaryDto queueSummary,
                                   long scopedStudentCount, List<ReviewQueueItemDto> topPendingItems) {
}
