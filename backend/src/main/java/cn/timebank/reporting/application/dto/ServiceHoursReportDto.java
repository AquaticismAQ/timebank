package cn.timebank.reporting.application.dto;

import java.time.Instant;
import java.util.List;

/** `GET /reports/service-hours` (contract §4.13) — approved hours by month, current academic year. */
public record ServiceHoursReportDto(Instant generatedAt, String academicYearId, List<MonthFigure> byMonth) {

    public record MonthFigure(String month, int hours) {
    }
}
