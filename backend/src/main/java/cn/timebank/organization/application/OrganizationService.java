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
}
