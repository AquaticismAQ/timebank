package cn.timebank.reporting.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * `GET /reports/service-hours` (contract §4.13) — approved service across an
 * academic year, at two resolutions.
 *
 * <p>{@code byDay} is what the trend line is drawn from (issue 133): a month is
 * twelve points across a school year, which is a shape too coarse to read — one
 * busy weekend and a quiet fortnight average into the same flat line. The
 * monthly figures stay because the peak/lowest summary beside the chart is a
 * statement about months, and deriving it from days would answer a different
 * question.</p>
 *
 * <p>{@code byDay} carries <b>minutes</b>, not hours: a day's service is often a
 * fraction of an hour and {@code byMonth}'s integer division would round it to
 * nothing. Durations are integer minutes everywhere else on the wire, and the
 * client formats.</p>
 *
 * <p>Every day in the academic year appears, including the empty ones. A line
 * chart plots its points evenly however far apart their dates really are, so a
 * sparse series would draw a fortnight's gap the same width as a weekend and
 * silently misstate the shape.</p>
 */
public record ServiceHoursReportDto(Instant generatedAt, String academicYearId,
                                     List<MonthFigure> byMonth, List<DayFigure> byDay) {

    public record MonthFigure(String month, int hours) {
    }

    /** @param day ISO {@code yyyy-MM-dd}; @param minutes approved service minutes on that day, zero if none */
    public record DayFigure(String day, int minutes) {
    }
}
