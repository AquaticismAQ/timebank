package cn.timebank.reporting.application.dto;

/** School-wide figures for `GET /dashboards/admin` — one row, aggregated in SQL (D6), never a Java loop over students. */
public record AdminStatsDto(long totalStudents, long activeStudents, long totalProjects, long activeProjects,
                             long pendingReviews, long approvedThisMonth, long totalServiceHoursThisYear,
                             Double reviewAvgHours) {
}
