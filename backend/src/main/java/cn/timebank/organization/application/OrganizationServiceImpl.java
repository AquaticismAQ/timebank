package cn.timebank.organization.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.OrganizationRequests.CreateOrganizationRequest;
import cn.timebank.organization.application.dto.OrganizationRequests.UpdateOrganizationRequest;
import cn.timebank.organization.domain.Organization;
import cn.timebank.organization.domain.OrganizationStatus;
import cn.timebank.organization.infrastructure.OrganizationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class OrganizationServiceImpl implements OrganizationService {

    private final OrganizationRepository repository;
    private final AuditService auditService;

    OrganizationServiceImpl(OrganizationRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrganizationDto> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationDto get(String id) {
        return toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public OrganizationDto create(CreateOrganizationRequest request, String actorId) {
        Organization.Type type = parseType(request.type());
        Organization org = new Organization(OpaqueIds.generate("org"), request.name(), type, Instant.now());
        org.setContactName(request.contactName());
        org.setContactEmail(request.contactEmail());
        org.setContactPhone(request.contactPhone());
        org.setAddress(request.address());
        repository.save(org);

        auditService.record(new AuditEntry(actorId, "organizations.manage", "organization.create",
                "organization", org.getId(), null, request, null));
        return toDto(org);
    }

    @Override
    @Transactional
    public OrganizationDto update(String id, UpdateOrganizationRequest request, String actorId) {
        Organization org = findOrThrow(id);
        ConcurrencyControl.checkVersion(org.getVersion(), request.version());

        if (request.name() != null) {
            org.setName(request.name());
        }
        if (request.contactName() != null) {
            org.setContactName(request.contactName());
        }
        if (request.contactEmail() != null) {
            org.setContactEmail(request.contactEmail());
        }
        if (request.contactPhone() != null) {
            org.setContactPhone(request.contactPhone());
        }
        if (request.address() != null) {
            org.setAddress(request.address());
        }
        org.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(actorId, "organizations.manage", "organization.update",
                "organization", org.getId(), null, request, null));
        return toDto(org);
    }

    @Override
    @Transactional
    public void archive(String id, String actorId) {
        Organization org = findOrThrow(id);
        org.setStatus(OrganizationStatus.ARCHIVED);
        org.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "organizations.manage", "organization.archive",
                "organization", org.getId(), null, null, null));
    }

    private Organization findOrThrow(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Organization not found."));
    }

    private Organization.Type parseType(String raw) {
        try {
            return Organization.Type.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidRequest("Unknown organization type: " + raw);
        }
    }

    private OrganizationDto toDto(Organization org) {
        // projectCount: hardcoded until B4's project table exists (§2.11 rule 3's
        // aggregate has nothing to aggregate over yet — see Docs/implementation/00-Status.md).
        return new OrganizationDto(org.getId(), org.getName(), org.getType().name().toLowerCase(),
                org.getContactName(), org.getContactEmail(), org.getContactPhone(), org.getAddress(),
                org.getStatus().name().toLowerCase(), 0, org.getVersion(), org.getCreatedAt());
    }
}
