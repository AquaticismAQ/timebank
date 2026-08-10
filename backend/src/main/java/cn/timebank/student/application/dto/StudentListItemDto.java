package cn.timebank.student.application.dto;

/**
 * `GET /students` row. No email, guardian name, or guardian contact —
 * contract §9: list responses must not return them. {@link StudentDetailDto}
 * is the only shape that may carry them, and only when the caller has
 * `students.manage`.
 */
public record StudentListItemDto(
        String id,
        String studentNumber,
        String displayName,
        String gradeId,
        int gradeLevel,
        String classId,
        String className,
        String status,
        int graduationYear,
        Long version) {
}
