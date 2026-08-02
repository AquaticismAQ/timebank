package cn.timebank.security.application;

import cn.timebank.security.domain.TeacherScopeAssignment;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
class DataScopeResolverImpl implements DataScopeResolver {

    private final TeacherScopeAssignmentRepository scopeAssignmentRepository;

    DataScopeResolverImpl(TeacherScopeAssignmentRepository scopeAssignmentRepository) {
        this.scopeAssignmentRepository = scopeAssignmentRepository;
    }

    @Override
    public ScopeDescriptor resolve(TimeBankPrincipal principal, String resourceType, boolean grantAll) {
        if (grantAll) {
            return new ScopeDescriptor.All();
        }

        List<TeacherScopeAssignment> assignments =
                scopeAssignmentRepository.findByUserIdAndResourceType(principal.id(), resourceType);
        if (assignments.isEmpty()) {
            return new ScopeDescriptor.Self();
        }

        // A resource type is expected to use one scope dimension at a time in
        // Phase 2's usage (grade OR class OR project, not several combined) —
        // revisit if a future phase genuinely needs multi-dimensional scope.
        if (assignments.stream().anyMatch(a -> a.getScopeType() == TeacherScopeAssignment.ScopeType.GRADE)) {
            return new ScopeDescriptor.Grades(valuesOf(assignments, TeacherScopeAssignment.ScopeType.GRADE));
        }
        if (assignments.stream().anyMatch(a -> a.getScopeType() == TeacherScopeAssignment.ScopeType.CLASS)) {
            return new ScopeDescriptor.Classes(valuesOf(assignments, TeacherScopeAssignment.ScopeType.CLASS));
        }
        return new ScopeDescriptor.Projects(valuesOf(assignments, TeacherScopeAssignment.ScopeType.PROJECT));
    }

    private Set<String> valuesOf(List<TeacherScopeAssignment> assignments, TeacherScopeAssignment.ScopeType type) {
        return assignments.stream()
                .filter(a -> a.getScopeType() == type)
                .map(TeacherScopeAssignment::getScopeValue)
                .collect(Collectors.toSet());
    }
}
