package cn.timebank.organization.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.cache.CacheConfig;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.i18n.LocalizedNames;
import cn.timebank.organization.application.dto.ServiceCategoryDto;
import cn.timebank.organization.domain.ServiceCategory;
import cn.timebank.organization.infrastructure.ServiceCategoryRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class ServiceCategoryServiceImpl implements ServiceCategoryService {

    private final ServiceCategoryRepository repository;
    private final AuditService auditService;

    ServiceCategoryServiceImpl(ServiceCategoryRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = CacheConfig.DICTIONARIES, key = "'serviceCategories:' + #resolvedLanguage")
    public java.util.List<ServiceCategoryDto> list(String resolvedLanguage) {
        return repository.findAll().stream()
                .map(c -> toDto(c, resolvedLanguage))
                .toList();
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.DICTIONARIES, allEntries = true)
    public ServiceCategoryDto create(CreateServiceCategoryRequest request, String actorId, String resolvedLanguage) {
        if (repository.findByCode(request.code()).isPresent()) {
            throw ApiException.stateConflict("Service category code is already in use.");
        }
        ServiceCategory category = new ServiceCategory(OpaqueIds.generate("cat"), request.code(),
                request.nameZh(), request.nameEn(), Instant.now());
        repository.save(category);

        auditService.record(new AuditEntry(actorId, "rules.manage", "serviceCategory.create",
                "serviceCategory", category.getId(), null, request, null));
        return toDto(category, resolvedLanguage);
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.DICTIONARIES, allEntries = true)
    public ServiceCategoryDto update(String id, UpdateServiceCategoryRequest request, String actorId, String resolvedLanguage) {
        ServiceCategory category = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Service category not found."));
        if (request.nameZh() != null) {
            category.setNameZh(request.nameZh());
        }
        if (request.nameEn() != null) {
            category.setNameEn(request.nameEn());
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }
        category.setUpdatedAt(Instant.now());

        auditService.record(new AuditEntry(actorId, "rules.manage", "serviceCategory.update",
                "serviceCategory", category.getId(), null, request, null));
        return toDto(category, resolvedLanguage);
    }

    private ServiceCategoryDto toDto(ServiceCategory category, String resolvedLanguage) {
        return new ServiceCategoryDto(category.getId(), category.getCode(),
                LocalizedNames.pick(resolvedLanguage, category.getNameZh(), category.getNameEn()), category.isActive());
    }
}
