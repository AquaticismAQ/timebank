package cn.timebank.account.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The balance view (contract §3.7); {@code balance} is a stored cache
 * (D12), never written directly outside {@link
 * cn.timebank.account.application.AccountServiceImpl#credit} — every change
 * is paired with an appended {@link AccountTransaction} in the same
 * transaction (invariant 1). One row per student, created lazily on first
 * credit rather than at student creation (no code path needs an account
 * before one is first earned into).
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "student_id", length = 32, nullable = false, updatable = false)
    private String studentId;

    @Setter
    @Column(name = "grade_id", length = 32, nullable = false)
    private String gradeId;

    @Setter
    @Column(name = "class_id", length = 32, nullable = false)
    private String classId;

    @Setter
    @Column(name = "balance", nullable = false)
    private int balance;

    @Setter
    @Column(name = "frozen", nullable = false)
    private boolean frozen;

    @Setter
    @Column(name = "frozen_reason")
    private String frozenReason;

    @Setter
    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Account(String id, String studentId, String gradeId, String classId, Instant now) {
        this.id = id;
        this.studentId = studentId;
        this.gradeId = gradeId;
        this.classId = classId;
        this.balance = 0;
        this.frozen = false;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
