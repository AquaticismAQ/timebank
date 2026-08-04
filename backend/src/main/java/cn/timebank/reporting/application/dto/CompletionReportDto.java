package cn.timebank.reporting.application.dto;

import java.time.Instant;
import java.util.List;

/** `GET /reports/completion` (contract §4.13) — compliance against the currently-effective annual required minutes (B2.9). */
public record CompletionReportDto(Instant generatedAt, String academicYearId, int requiredMinutes, List<GradeFigure> byGrade) {

    public record GradeFigure(String gradeId, String gradeName, int totalStudents, int completedStudents,
                               double completionRate) {
    }
}
