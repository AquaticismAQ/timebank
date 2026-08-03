package cn.timebank.caseflow.application;

import cn.timebank.caseflow.domain.Case;
import cn.timebank.security.application.ScopeFields;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

/** B1.7's mechanism, wired to `cases` — the exit criterion "teacher data scope applies to appeals/supplements/corrections" (B5). */
class CaseScopeFields implements ScopeFields<Case> {

    @Override
    public Path<String> ownerIdPath(Root<Case> root) {
        return root.get("studentId");
    }

    @Override
    public Path<String> gradeIdPath(Root<Case> root) {
        return root.get("gradeId");
    }

    @Override
    public Path<String> classIdPath(Root<Case> root) {
        return root.get("classId");
    }

    @Override
    public Path<String> projectIdPath(Root<Case> root) {
        throw new UnsupportedOperationException("Cases are not project-scoped.");
    }
}
