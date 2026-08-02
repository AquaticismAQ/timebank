package cn.timebank.student.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A static dictionary entry (grade_6..grade_11) — not year-scoped. */
@Entity
@Table(name = "grade")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Grade {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Column(name = "name_zh", length = 100, nullable = false)
    private String nameZh;

    @Column(name = "name_en", length = 100, nullable = false)
    private String nameEn;

    @Column(name = "sequence", nullable = false)
    private int sequence;
}
