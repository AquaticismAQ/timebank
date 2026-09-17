package cn.timebank.reporting.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * `GET /reports/top-projects` (contract §4.13) — highest-participation
 * projects scheduled inside the selected academic year (issue 093 §1; the
 * current year when the caller names none). Before that it was the only
 * report with no period at all: it ranked every project ever run and summed
 * every approved record against them, under a header naming one year.
 */
public record TopProjectsReportDto(Instant generatedAt, String academicYearId, List<ProjectFigure> topProjects) {

    public record ProjectFigure(String projectId, String title, long participants, long totalMinutes) {
    }
}
