package cn.timebank.enrollment.infrastructure;

import cn.timebank.enrollment.domain.Enrollment;
import cn.timebank.enrollment.domain.EnrollmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, String>, JpaSpecificationExecutor<Enrollment> {

    Optional<Enrollment> findByProjectIdAndStudentId(String projectId, String studentId);

    /**
     * Ids only, for the project timeline (issue 158): an enrollment's audit rows
     * are recorded against the enrollment, and nothing on those rows names the
     * project, so this table is the only link between "project X" and "the
     * registration events that belong to it".
     */
    @Query("select e.id from Enrollment e where e.projectId = :projectId")
    List<String> findIdsByProjectId(@Param("projectId") String projectId);

    long countByProjectIdAndStatus(String projectId, EnrollmentStatus status);

    /** Waitlist promotion order (B4.6) — earliest applicant first. */
    Page<Enrollment> findByProjectIdAndStatusOrderByAppliedAtAsc(String projectId, EnrollmentStatus status, Pageable pageable);
}
