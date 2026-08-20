package cn.timebank.security.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.security.domain.TeacherScopeAssignment;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
class TeacherScopeAssignmentServiceImpl implements TeacherScopeAssignmentService {

    /** Every resourceType a {@code ScopeFields} implementation exists for today. */
    private static final List<String> MANAGED_RESOURCE_TYPES =
            List.of("serviceRecords", "eventProposals", "students", "accounts", "cases");

    private final TeacherScopeAssignmentRepository repository;

    TeacherScopeAssignmentServiceImpl(TeacherScopeAssignmentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> gradesOf(String userId) {
        return repository.findByUserIdAndResourceType(userId, MANAGED_RESOURCE_TYPES.get(0)).stream()
                .filter(a -> a.getScopeType() == TeacherScopeAssignment.ScopeType.GRADE)
                .map(TeacherScopeAssignment::getScopeValue)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> userIdsAssignedToGrade(String resourceType, String gradeId) {
        return repository.findByResourceTypeAndScopeTypeAndScopeValue(
                        resourceType, TeacherScopeAssignment.ScopeType.GRADE, gradeId).stream()
                .map(TeacherScopeAssignment::getUserId)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional
    public void replaceGradeScope(String userId, Set<String> gradeIds) {
        repository.deleteByUserId(userId);
        Instant now = Instant.now();
        for (String resourceType : MANAGED_RESOURCE_TYPES) {
            for (String gradeId : gradeIds) {
                repository.save(new TeacherScopeAssignment(OpaqueIds.generate("scope"), userId, resourceType,
                        TeacherScopeAssignment.ScopeType.GRADE, gradeId, now));
            }
        }
    }
}
