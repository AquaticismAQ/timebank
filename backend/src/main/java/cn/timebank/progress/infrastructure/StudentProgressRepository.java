package cn.timebank.progress.infrastructure;

import cn.timebank.progress.domain.StudentProgress;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface StudentProgressRepository extends JpaRepository<StudentProgress, String> {

    /** Row-locked, mirroring {@code AccountRepository.findWithLockById} — the approval transaction updates both under lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StudentProgress p where p.studentId = :studentId")
    Optional<StudentProgress> findWithLockByStudentId(String studentId);
}
