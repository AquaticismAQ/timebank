package cn.timebank.servicerecord.application.dto;

/** What {@code progress} needs from `servicerecord` for the annual figures on {@code GET /students/{id}/progress} (B3.11). */
public record AnnualProgressSummary(int approvedMinutes, int pendingMinutes) {
}
