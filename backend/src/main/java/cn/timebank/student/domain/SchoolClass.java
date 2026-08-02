package cn.timebank.student.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Named {@code SchoolClass}, not {@code Class} — the latter shadows {@code
 * java.lang.Class} everywhere in this file and every caller's. The table
 * itself is plain {@code class} (not a reserved word in MySQL).
 */
@Entity
@Table(name = "class")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchoolClass {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Column(name = "name_zh", length = 100, nullable = false)
    private String nameZh;

    @Column(name = "name_en", length = 100, nullable = false)
    private String nameEn;

    @Column(name = "grade_id", length = 32, nullable = false)
    private String gradeId;

    @Column(name = "academic_year_id", length = 32, nullable = false)
    private String academicYearId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
