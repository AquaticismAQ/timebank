package cn.timebank.reporting.application.dto;

import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.review.application.dto.ReviewQueueItemDto;

import java.time.Instant;
import java.util.List;

/** `GET /dashboards/admin` (contract §4.13) — school-wide dashboard. */
public record DashboardAdminDto(Instant generatedAt, AdminStatsDto stats, List<ReviewQueueItemDto> pendingReviews,
                                 List<ProjectDto> activeProjects) {
}
