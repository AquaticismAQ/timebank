package cn.timebank.rules.application;

import cn.timebank.rules.application.dto.RuleRequests.CreateRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.PublishRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.UpdateRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.ValidationImpactDto;
import cn.timebank.rules.application.dto.RuleVersionDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Contract §4.12. */
public interface RuleService {

    Page<RuleVersionDto> list(Pageable pageable);

    RuleVersionDto get(String id);

    RuleVersionDto createDraft(CreateRuleRequest request, String actorId);

    RuleVersionDto updateDraft(String id, UpdateRuleRequest request, String actorId);

    ValidationImpactDto validate(String id);

    RuleVersionDto publish(String id, PublishRuleRequest request, String actorId);

    void archive(String id, String actorId);
}
