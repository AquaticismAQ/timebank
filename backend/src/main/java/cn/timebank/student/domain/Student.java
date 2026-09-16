package cn.timebank.student.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code studentNumber} is a plain unique index, not a cohort-compound
 * key — the number space becomes reusable once B6.3's purge deletes a
 * graduated cohort's rows, which is what D8's guardrail actually asks for.
 *
 * {@code graduationYear} is the purge key B6.3 depends on — present from
 * this entity's first migration, never retrofitted.
 *
 * This entity deliberately carries **no guardian name, guardian contact, or
 * roster email**. They existed from V4 until V26 and were dropped under data
 * minimization (`Docs/optimization/08-SecurityPrivacyHardening.md` §3.1): no
 * screen ever rendered them, so they were pure breach surface. Do not
 * reintroduce them without a real consumer.
 */
@Entity
@Table(name = "student")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Student extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Setter
    @Column(name = "student_number", length = 32, nullable = false)
    private String studentNumber;

    @Setter
    @Column(name = "display_name", length = 100, nullable = false)
    private String displayName;

    @Setter
    @Column(name = "grade_id", length = 32, nullable = false)
    private String gradeId;

    @Setter
    @Column(name = "class_id", length = 32, nullable = false)
    private String classId;

    @Column(name = "enrollment_academic_year_id", length = 32, nullable = false, updatable = false)
    private String enrollmentAcademicYearId;

    @Column(name = "graduation_year", nullable = false)
    private int graduationYear;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StudentStatus status;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDate joinedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Student(String id, String studentNumber, String displayName, String gradeId, String classId,
                    String enrollmentAcademicYearId, int graduationYear, LocalDate joinedAt, Instant now) {
        this.id = id;
        this.studentNumber = studentNumber;
        this.displayName = displayName;
        this.gradeId = gradeId;
        this.classId = classId;
        this.enrollmentAcademicYearId = enrollmentAcademicYearId;
        this.graduationYear = graduationYear;
        this.status = StudentStatus.ACTIVE;
        this.joinedAt = joinedAt;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
