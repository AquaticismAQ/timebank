package cn.timebank.security.infrastructure;

import cn.timebank.security.domain.TeacherScopeAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeacherScopeAssignmentRepository extends JpaRepository<TeacherScopeAssignment, String> {

    List<TeacherScopeAssignment> findByUserIdAndResourceType(String userId, String resourceType);
}
