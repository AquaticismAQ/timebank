package cn.timebank.organization.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.organization.application.OrganizationService;
import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.OrganizationRequests.CreateOrganizationRequest;
import cn.timebank.organization.application.dto.OrganizationRequests.UpdateOrganizationRequest;
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

/** Contract §4.10. */
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private static final Set<String> SORTABLE = Set.of("id", "name", "createdAt");

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('organizations.read')")
    public ApiResponse<List<OrganizationDto>> list(PageQuery query) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<OrganizationDto> page = organizationService.list(pageable);
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('organizations.read')")
    public ApiResponse<OrganizationDto> get(@PathVariable String id) {
        return ApiResponse.of(organizationService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('organizations.manage')")
    public ApiResponse<OrganizationDto> create(@Valid @RequestBody CreateOrganizationRequest request, Authentication auth) {
        return ApiResponse.of(organizationService.create(request, principalId(auth)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('organizations.manage')")
    public ApiResponse<OrganizationDto> update(@PathVariable String id, @Valid @RequestBody UpdateOrganizationRequest request,
                                                Authentication auth) {
        return ApiResponse.of(organizationService.update(id, request, principalId(auth)));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('organizations.manage')")
    public ApiResponse<Void> archive(@PathVariable String id, Authentication auth) {
        organizationService.archive(id, principalId(auth));
        return ApiResponse.of(null);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
