package cn.timebank.organization.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.pagination.FilterSpecifications;
import cn.timebank.organization.application.dto.OrganizationDto;
import cn.timebank.organization.application.dto.OrganizationRequests.CreateOrganizationRequest;
import cn.timebank.organization.application.dto.OrganizationRequests.UpdateOrganizationRequest;
import cn.timebank.organization.domain.Organization;
import cn.timebank.organization.domain.OrganizationStatus;
import cn.timebank.organization.infrastructure.OrganizationRepository;
import cn.timebank.organization.infrastructure.ProjectCountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class OrganizationServiceImpl implements OrganizationService {

    private final OrganizationRepository repository;
    private final AuditService auditService;
    private final ProjectCountRepository projectCountRepository;

    OrganizationServiceImpl(OrganizationRepository repository, AuditService auditService,
                             ProjectCountRepository projectCountRepository) {
        this.repository = repository;
        this.auditService = auditService;
        this.projectCountRepository = projectCountRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrganizationDto> list(Pageable pageable, String type, String status, String search) {
        Specification<Organization> spec = FilterSpecifications.and(
                FilterSpecifications.equalToEnum("type", Organization.Type.class, type == null ? null : type.toUpperCase()),
                FilterSpecifications.equalToEnum("status", OrganizationStatus.class, status == null ? null : status.toUpperCase()),
                FilterSpecifications.searchAnyContains(search, "name"));
        return repository.findAll(spec, pageable).map(this::toDto);
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
        org.setDescription(request.description());
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
        rejectIfSchoolOrganization(org, "The school organization cannot be edited here — use school settings instead.");
        ConcurrencyControl.checkVersion(org.getVersion(), request.version());

        if (request.name() != null) {
            org.setName(request.name());
        }
        if (request.description() != null) {
            org.setDescription(request.description());
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
        rejectIfSchoolOrganization(org, "The school organization cannot be archived.");
        org.setStatus(OrganizationStatus.ARCHIVED);
        org.setUpdatedAt(Instant.now());
        auditService.record(new AuditEntry(actorId, "organizations.manage", "organization.archive",
                "organization", org.getId(), null, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationDto getSchoolOrganization() {
        return toDto(repository.findBySchoolOrganizationTrue()
                .orElseThrow(() -> new IllegalStateException(
                        "No school organization row exists — the startup health check should have prevented this.")));
    }

    private void rejectIfSchoolOrganization(Organization org, String message) {
        if (org.isSchoolOrganization()) {
            throw ApiException.stateConflict(message);
        }
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
        // Organization lists are small (school-scale, dozens not thousands) —
        // a per-row count query here is not the N+1 discipline §16.1 is
        // about for the busy service-record/review surfaces.
        int projectCount = projectCountRepository.countByOrganizationId(org.getId());
        return new OrganizationDto(org.getId(), org.getName(), org.getType().name().toLowerCase(), org.getDescription(),
                org.getContactName(), org.getContactEmail(), org.getContactPhone(), org.getAddress(),
                org.getStatus().name().toLowerCase(), org.isSchoolOrganization(), projectCount, org.getVersion(), org.getCreatedAt());
    }
}
