package cn.timebank.organization.application;

import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.OrganizationRequests.CreateOrganizationRequest;
import cn.timebank.organization.application.dto.OrganizationRequests.UpdateOrganizationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Contract §4.10. */
public interface OrganizationService {

    Page<OrganizationDto> list(Pageable pageable);

    OrganizationDto get(String id);

    OrganizationDto create(CreateOrganizationRequest request, String actorId);

    OrganizationDto update(String id, UpdateOrganizationRequest request, String actorId);

    void archive(String id, String actorId);
}
