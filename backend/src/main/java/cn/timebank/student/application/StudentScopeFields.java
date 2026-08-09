package cn.timebank.student.application;

import cn.timebank.security.application.ScopeFields;
import cn.timebank.student.domain.Student;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

/** B1.7's data-scope mechanism, wired to {@code Student} (B2's endpoints, picked up post-hoc). */
class StudentScopeFields implements ScopeFields<Student> {

    @Override
    public Path<String> ownerIdPath(Root<Student> root) {
        return root.get("id");
    }

    @Override
    public Path<String> gradeIdPath(Root<Student> root) {
        return root.get("gradeId");
    }

    @Override
    public Path<String> classIdPath(Root<Student> root) {
        return root.get("classId");
    }

    @Override
    public Path<String> projectIdPath(Root<Student> root) {
        throw new UnsupportedOperationException("Students are not project-scoped.");
    }
}
