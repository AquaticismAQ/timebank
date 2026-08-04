package cn.timebank.reporting.application.dto;

import java.time.Instant;
import java.util.List;

/** `GET /reports/participation` (contract §4.13). Backed by `grade_participation_summary` when materialized (B5.8), computed live otherwise. */
public record ParticipationReportDto(Instant generatedAt, String academicYearId, List<GradeFigure> byGrade) {

    public record GradeFigure(String gradeId, String gradeName, int totalStudents, int participatedStudents,
                               double participationRate) {
    }
}
