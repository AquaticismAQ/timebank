package cn.timebank.student.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public final class StudentRequests {

    private StudentRequests() {
    }

    /** No guardian or email fields: dropped in V26 under data minimization (`Docs/optimization/08-SecurityPrivacyHardening.md` §3.1). */
    public record CreateStudentRequest(
            @NotBlank String studentNumber,
            @NotBlank String displayName,
            @NotBlank String gradeId,
            @NotBlank String classId,
            @NotBlank String enrollmentAcademicYearId,
            @Min(2000) int graduationYear,
            @NotNull LocalDate joinedAt) {
    }

    /** `PATCH /students/{id}` — the authoritative profile only (contract §4.9); never status (action endpoints own that). */
    public record UpdateStudentRequest(
            String studentNumber,
            String displayName,
            String gradeId,
            String classId,
            @NotNull Long version) {
    }
}
