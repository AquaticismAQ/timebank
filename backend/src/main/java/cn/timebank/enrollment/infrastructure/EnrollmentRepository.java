package cn.timebank.enrollment.infrastructure;

import cn.timebank.enrollment.domain.Enrollment;
import cn.timebank.enrollment.domain.EnrollmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface EnrollmentRepository extends JpaRepository<Enrollment, String>, JpaSpecificationExecutor<Enrollment> {

    Optional<Enrollment> findByProjectIdAndStudentId(String projectId, String studentId);

    long countByProjectIdAndStatus(String projectId, EnrollmentStatus status);

    /** Waitlist promotion order (B4.6) — earliest applicant first. */
    Page<Enrollment> findByProjectIdAndStatusOrderByAppliedAtAsc(String projectId, EnrollmentStatus status, Pageable pageable);
}
