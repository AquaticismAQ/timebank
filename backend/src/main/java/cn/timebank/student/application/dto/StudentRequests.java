package cn.timebank.student.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public final class StudentRequests {

    private StudentRequests() {
    }

    public record CreateStudentRequest(
            @NotBlank String studentNumber,
            @NotBlank String displayName,
            String email,
            @NotBlank String gradeId,
            @NotBlank String classId,
            @NotBlank String enrollmentAcademicYearId,
            @Min(2000) int graduationYear,
            @NotNull LocalDate joinedAt,
            String guardianName,
            String guardianContact) {
    }

    /** `PATCH /students/{id}` — the authoritative profile only (contract §4.9); never status (action endpoints own that). */
    public record UpdateStudentRequest(
            String displayName,
            String email,
            String gradeId,
            String classId,
            String guardianName,
            String guardianContact,
            @NotNull Long version) {
    }
}
