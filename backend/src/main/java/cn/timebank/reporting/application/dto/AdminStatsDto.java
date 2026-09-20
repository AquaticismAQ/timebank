package cn.timebank.reporting.application.dto;

/**
 * The figures behind `GET /dashboards/admin` — one row, aggregated in SQL (D6),
 * never a Java loop over students.
 *
 * <b>Scoped to the caller since issue 127</b>, not school-wide: a teacher's
 * dashboard covers the grades they are responsible for, an administrator's
 * covers the school. The two used to be identical.
 *
 * {@code avgCompletionRate} is a fraction in 0..1, or null where no grade in
 * scope has a published requirement to measure against. The frontend has read
 * this field since the card was built; it was never sent.
 */
public record AdminStatsDto(long totalStudents, long activeStudents, long totalProjects, long activeProjects,
                             long pendingReviews, long approvedThisMonth, long totalServiceHoursThisYear,
                             Double reviewAvgHours, Double avgCompletionRate) {
}
