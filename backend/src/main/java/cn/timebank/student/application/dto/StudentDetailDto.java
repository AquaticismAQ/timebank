package cn.timebank.student.application.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * `GET /students/{id}`.
 *
 * <p>This record used to carry {@code email}, {@code guardianName} and
 * {@code guardianContact}, nulled out unless the caller held
 * `students.manage`. V26 dropped all three columns under data minimization
 * (`Docs/optimization/08-SecurityPrivacyHardening.md` §3.1), so **no field
 * here is permission-gated any more** — see {@code StudentService.getDetail}'s
 * {@code includeSensitiveFields} parameter, which now only governs whether the
 * access is audited.</p>
 */
public record StudentDetailDto(
        String id,
        String studentNumber,
        String displayName,
        String gradeId,
        int gradeLevel,
        String classId,
        String className,
        String status,
        int graduationYear,
        LocalDate joinedAt,
        Long version,
        Instant createdAt,
        Instant updatedAt) {
}
