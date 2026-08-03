package cn.timebank.account.application;

import cn.timebank.account.domain.Account;
import cn.timebank.security.application.ScopeFields;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

/** B1.7's mechanism, wired to a real entity for the second time (after `servicerecord`). */
class AccountScopeFields implements ScopeFields<Account> {

    @Override
    public Path<String> ownerIdPath(Root<Account> root) {
        return root.get("studentId");
    }

    @Override
    public Path<String> gradeIdPath(Root<Account> root) {
        return root.get("gradeId");
    }

    @Override
    public Path<String> classIdPath(Root<Account> root) {
        return root.get("classId");
    }

    @Override
    public Path<String> projectIdPath(Root<Account> root) {
        throw new UnsupportedOperationException("Accounts are not project-scoped.");
    }
}
