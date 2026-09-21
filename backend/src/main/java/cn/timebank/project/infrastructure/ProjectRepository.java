package cn.timebank.project.infrastructure;

import cn.timebank.project.domain.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, String>, JpaSpecificationExecutor<Project> {

    /**
     * B4.7: {@code SELECT ... FOR UPDATE} on the project row, the single
     * locked read `EnrollmentServiceImpl.register` uses to serialize
     * capacity checks — mirrors {@code AccountRepository
     * .findWithLockByStudentId}'s "one locked query only" lesson from B3
     * exactly (an unlocked pre-check here would reintroduce that bug).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findWithLockById(String id);

    /**
     * {@code eligible_grade_ids} is a MySQL JSON column (a project can carry
     * multiple grades), not a single scalar — `FilterSpecifications.equalTo`
     * can't express "this JSON array contains X" as a JPA Criteria
     * predicate against a `@Convert`ed collection column, so this is native
     * SQL instead (same {@code JSON_CONTAINS(col, JSON_QUOTE(:x))} idiom
     * {@code ServiceRecordReferenceRepository} already uses for
     * `evidence_file_ids`). {@code ProjectServiceImpl.list} folds the
     * result into an `id in (...)` Specification alongside its other
     * filters, rather than this method doing pagination itself.
     */
    @Query(value = "select id from project where JSON_CONTAINS(eligible_grade_ids, JSON_QUOTE(:gradeId))", nativeQuery = true)
    List<String> findIdsByEligibleGrade(@Param("gradeId") String gradeId);

    /**
     * Projects that name no eligible grade at all.
     *
     * Such a project restricts nobody, so it is visible to every student
     * (issue 129) — the alternative reading, "open to no one", would hide it
     * from the whole school on a technicality. The admin form requires at least
     * one grade, so these arrive only through the API and through proposal
     * approval; they are real rows either way and a student must be able to see
     * one.
     */
    @Query(value = "select id from project where eligible_grade_ids is null"
            + " or JSON_LENGTH(eligible_grade_ids) = 0", nativeQuery = true)
    List<String> findIdsWithNoEligibleGrades();

    /**
     * Projects whose stored status the clock has overtaken (issue 145).
     *
     * <p>The four conditions are the four time-driven edges of
     * {@code ProjectStateMachine}, and deliberately only those: `DRAFT`,
     * `COMPLETED` and `ARCHIVED` have no clock edge leaving them, so a sweep
     * that selected every project would load the whole table to do nothing to
     * most of it.</p>
     *
     * <p>One pass is enough even for a project that has fallen several
     * boundaries behind: {@code syncTimeBasedStatus} walks the chain with
     * sequential `if`s, so `PUBLISHED` can reach `COMPLETED` in a single
     * call.</p>
     */
    @Query("""
            select p from Project p
            where (p.status = cn.timebank.project.domain.ProjectStatus.PUBLISHED and p.registrationOpenAt <= :now)
               or (p.status = cn.timebank.project.domain.ProjectStatus.REGISTRATION_OPEN and p.registrationCloseAt <= :now)
               or (p.status = cn.timebank.project.domain.ProjectStatus.CLOSED and p.startAt <= :now)
               or (p.status = cn.timebank.project.domain.ProjectStatus.IN_PROGRESS and p.endAt <= :now)
            """)
    List<Project> findOvertakenByClock(@Param("now") Instant now);
}
