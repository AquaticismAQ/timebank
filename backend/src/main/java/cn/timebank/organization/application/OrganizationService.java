package cn.timebank.organization.application;

import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.OrganizationRequests.CreateOrganizationRequest;
import cn.timebank.organization.application.dto.OrganizationRequests.UpdateOrganizationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Contract §4.10. */
public interface OrganizationService {

    /** {@code type}/{@code status}: contract §2.7 {@code filter[...]}, {@code search} matches {@code name} only; null/blank means unfiltered. */
    Page<OrganizationDto> list(Pageable pageable, String type, String status, String search);

    OrganizationDto get(String id);

    OrganizationDto create(CreateOrganizationRequest request, String actorId);

    OrganizationDto update(String id, UpdateOrganizationRequest request, String actorId);

    void archive(String id, String actorId);

    /**
     * The one {@code isSchoolOrganization = true} row (Docs/optimization/03-ServiceRecordFormOptimization.md
     * §4.1) — the host organization for every project, and the source `ProjectServiceImpl`/
     * `ServiceRecordServiceImpl` resolve it from rather than trusting a client-supplied id.
     * Throws if the row is missing; a missing row means the startup health check
     * (`SchoolOrganizationHealthCheck`) already failed to bring the app up in the first place,
     * so this is a genuine invariant violation, not a normal "not found".
     */
    OrganizationDto getSchoolOrganization();
}
