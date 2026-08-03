package cn.timebank.servicerecord.application;

import cn.timebank.security.application.ScopeFields;
import cn.timebank.servicerecord.domain.ServiceRecord;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

/** B1.7's data-scope mechanism, wired to a real entity for the first time (B3.1's `list`). */
class ServiceRecordScopeFields implements ScopeFields<ServiceRecord> {

    @Override
    public Path<String> ownerIdPath(Root<ServiceRecord> root) {
        return root.get("studentId");
    }

    @Override
    public Path<String> gradeIdPath(Root<ServiceRecord> root) {
        return root.get("gradeId");
    }

    @Override
    public Path<String> classIdPath(Root<ServiceRecord> root) {
        return root.get("classId");
    }

    @Override
    public Path<String> projectIdPath(Root<ServiceRecord> root) {
        return root.get("projectId");
    }
}
