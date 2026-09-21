package cn.timebank.lifecycle.application.dto;

import java.util.Map;

/**
 * What the rollover did, for the report Design/*&#47;14-AnnualRollover.md §7
 * requires. The process is unattended, so this is its only output and has to
 * explain itself without help.
 *
 * @param outcome RAN, SKIPPED (the year was already rolled over) or FAILED
 * @param promotedByGrade target grade id to count, so "who moved where" is legible
 * @param emptyIntakeGrades the stage entry grades left with no students — grades 6
 *        and 9 are established by roster import, not by this process, so being
 *        empty right after a rollover is expected and must be stated rather than
 *        left for someone to discover
 * @param inconsistentGrades students whose stored grade no longer matches their
 *        enrolment cohort (§5); non-zero means a year was missed or a student
 *        repeated
 */
public record AnnualRolloverReportDto(
        String runId,
        String academicYearId,
        String outcome,
        int studentsPromoted,
        int studentsGraduated,
        int loginsDeactivated,
        int accountsResynced,
        int classesCreated,
        int studentsSkipped,
        int inconsistentGrades,
        Map<String, Integer> promotedByGrade,
        java.util.List<String> emptyIntakeGrades,
        String message) {
}
