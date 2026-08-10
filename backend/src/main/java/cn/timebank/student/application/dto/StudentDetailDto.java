package cn.timebank.student.application.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * `GET /students/{id}`. {@code email}, {@code guardianName}, and {@code
 * guardianContact} are null unless the caller has `students.manage` — the
 * service, not this record, decides that and audits the access (contract
 * §9). A null here is "not shown to you", never "not on file".
 */
public record StudentDetailDto(
        String id,
        String studentNumber,
        String displayName,
        String email,
        String gradeId,
        int gradeLevel,
        String classId,
        String className,
        String status,
        int graduationYear,
        String guardianName,
        String guardianContact,
        LocalDate joinedAt,
        Long version,
        Instant createdAt,
        Instant updatedAt) {
}
