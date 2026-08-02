package cn.timebank.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Single row (D8: single-tenant per deployment). School config lives in data, not deployment properties (D8 guardrail 2). */
@Entity
@Table(name = "school_info")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchoolInfo {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Setter
    @Column(name = "name", nullable = false)
    private String name;

    @Setter
    @Column(name = "full_name")
    private String fullName;

    @Setter
    @Column(name = "student_dev_center")
    private String studentDevCenter;

    @Setter
    @Column(name = "contact")
    private String contact;

    @Setter
    @Column(name = "default_language", length = 8, nullable = false)
    private String defaultLanguage;

    @Setter
    @Column(name = "current_academic_year_code", length = 16)
    private String currentAcademicYearCode;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
