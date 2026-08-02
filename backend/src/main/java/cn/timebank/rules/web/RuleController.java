package cn.timebank.rules.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.rules.application.RuleService;
import cn.timebank.rules.application.dto.RuleRequests.CreateRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.PublishRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.UpdateRuleRequest;
import cn.timebank.rules.application.dto.RuleRequests.ValidationImpactDto;
import cn.timebank.rules.application.dto.RuleVersionDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Contract §4.12. */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private static final Set<String> SORTABLE = Set.of("id", "createdAt", "effectiveFrom");

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('rules.read')")
    public ApiResponse<List<RuleVersionDto>> list(PageQuery query) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<RuleVersionDto> page = ruleService.list(pageable);
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('rules.read')")
    public ApiResponse<RuleVersionDto> get(@PathVariable String id) {
        return ApiResponse.of(ruleService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('rules.manage')")
    public ApiResponse<RuleVersionDto> create(@Valid @RequestBody CreateRuleRequest request, Authentication auth) {
        return ApiResponse.of(ruleService.createDraft(request, principalId(auth)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('rules.manage')")
    public ApiResponse<RuleVersionDto> update(@PathVariable String id, @Valid @RequestBody UpdateRuleRequest request,
                                               Authentication auth) {
        return ApiResponse.of(ruleService.updateDraft(id, request, principalId(auth)));
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasAuthority('rules.manage')")
    public ApiResponse<ValidationImpactDto> validate(@PathVariable String id) {
        return ApiResponse.of(ruleService.validate(id));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('rules.publish')")
    public ApiResponse<RuleVersionDto> publish(@PathVariable String id, @Valid @RequestBody PublishRuleRequest request,
                                                Authentication auth) {
        return ApiResponse.of(ruleService.publish(id, request, principalId(auth)));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('rules.manage')")
    public ApiResponse<Void> archive(@PathVariable String id, Authentication auth) {
        ruleService.archive(id, principalId(auth));
        return ApiResponse.of(null);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
