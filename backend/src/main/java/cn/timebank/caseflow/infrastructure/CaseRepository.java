package cn.timebank.caseflow.infrastructure;

import cn.timebank.caseflow.domain.Case;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CaseRepository extends JpaRepository<Case, String>, JpaSpecificationExecutor<Case> {
}
