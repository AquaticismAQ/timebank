package cn.timebank.reporting.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * `GET /reports/completion` (contract §4.13) — compliance against the
 * currently-effective annual required minutes (B2.9). No single school-wide
 * required-minutes figure exists any more (middle school 6-8 and high
 * school 9-11 have different annual requirements — Design/chinese/
 * 01-TimeBankDesign.md §6), so {@code requiredMinutes} moved onto each
 * {@code GradeFigure} instead of living at the top level.
 */
public record CompletionReportDto(Instant generatedAt, String academicYearId, List<GradeFigure> byGrade) {

    public record GradeFigure(String gradeId, String gradeName, int totalStudents, int completedStudents,
                               double completionRate, int requiredMinutes) {
    }
}
