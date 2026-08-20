package cn.timebank.security.infrastructure;

import cn.timebank.security.domain.TeacherScopeAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TeacherScopeAssignmentRepository extends JpaRepository<TeacherScopeAssignment, String> {

    List<TeacherScopeAssignment> findByUserIdAndResourceType(String userId, String resourceType);

    /**
     * 07-RoleFinetune.md §2.10's reverse of {@link #findByUserIdAndResourceType} — every
     * assignment row matching a given grade, for callers that need "who covers this grade"
     * rather than "which grades does this user cover" (e.g. proposal-approval publish
     * notifications).
     */
    List<TeacherScopeAssignment> findByResourceTypeAndScopeTypeAndScopeValue(
            String resourceType, TeacherScopeAssignment.ScopeType scopeType, String scopeValue);

    void deleteByUserId(String userId);
}
