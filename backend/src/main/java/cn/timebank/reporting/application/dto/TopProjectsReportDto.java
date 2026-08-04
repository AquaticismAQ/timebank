package cn.timebank.reporting.application.dto;

import java.time.Instant;
import java.util.List;

/** `GET /reports/top-projects` (contract §4.13) — highest-participation projects, current academic year. */
public record TopProjectsReportDto(Instant generatedAt, List<ProjectFigure> topProjects) {

    public record ProjectFigure(String projectId, String title, long participants, long totalMinutes) {
    }
}
