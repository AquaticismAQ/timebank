package cn.timebank.rules.infrastructure;

import cn.timebank.rules.domain.RuleStatus;
import cn.timebank.rules.domain.RuleVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface RuleVersionRepository extends JpaRepository<RuleVersion, String> {

    Page<RuleVersion> findAll(Pageable pageable);

    List<RuleVersion> findByStatusAndEffectiveFromLessThanEqual(RuleStatus status, Instant at);
}
