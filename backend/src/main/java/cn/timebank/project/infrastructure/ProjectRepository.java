package cn.timebank.project.infrastructure;

import cn.timebank.project.domain.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
}
