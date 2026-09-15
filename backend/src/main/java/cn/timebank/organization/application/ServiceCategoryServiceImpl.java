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
import org.springframework.dao.DataIntegrityViolationException;
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
                .sorted(java.util.Comparator.comparing(ServiceCategory::getCode,
                        (a, b) -> {
                            boolean aOther = a.endsWith("_other");
                            boolean bOther = b.endsWith("_other");
                            if (aOther != bOther) return aOther ? 1 : -1;
                            return a.compareTo(b);
                        }))
                .map(c -> toDto(c, resolvedLanguage))
                .toList();
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.DICTIONARIES, allEntries = true)
    public ServiceCategoryDto create(CreateServiceCategoryRequest request, String actorId, String resolvedLanguage) {
        // Either name colliding is a duplicate: two categories that read the
        // same in one language are indistinguishable to whoever is picking one
        // in that language, whatever the other name says (issue 078).
        String nameZh = request.nameZh().trim();
        String nameEn = request.nameEn().trim();
        requireNameIsFree(nameZh, nameEn, null);

        ServiceCategory category = new ServiceCategory(OpaqueIds.generate("cat"), generateCode(nameEn),
                nameZh, nameEn, Instant.now());
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
        // Same duplicate rule as create — renaming is the other way to collide.
        requireNameIsFree(request.nameZh(), request.nameEn(), id);
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

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.DICTIONARIES, allEntries = true)
    public void delete(String id, String actorId) {
        ServiceCategory category = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Service category not found."));

        // service_record, project and event_proposal all have an FK onto this row.
        // Deleting one that is referenced would fail at the database and surface as
        // an opaque 500 (issue 059's shape), so the violation is caught and turned
        // into a conflict that tells the administrator what to do instead.
        //
        // Attempting the delete rather than counting references first is deliberate:
        // ModuleBoundaryTest forbids `organization` from depending on those modules'
        // domain/infrastructure packages, and the database is the authority anyway.
        repository.delete(category);
        try {
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.stateConflict(
                    "This service category is in use by existing records and cannot be deleted. Deactivate it instead.");
        }

        auditService.record(new AuditEntry(actorId, "rules.manage", "serviceCategory.delete",
                "serviceCategory", category.getId(), category.getCode(), null, null));
    }

    /**
     * Either name colliding is a duplicate: two categories that read the same
     * in one language are indistinguishable to whoever is picking one in that
     * language, whatever their other name says (issue 078). Comparison ignores
     * case and surrounding whitespace, so "Other " cannot shadow "other".
     */
    private void requireNameIsFree(String nameZh, String nameEn, String excludeId) {
        repository.findAll().stream()
                .filter(c -> !c.getId().equals(excludeId))
                .filter(c -> (nameZh != null && c.getNameZh().trim().equalsIgnoreCase(nameZh.trim()))
                        || (nameEn != null && c.getNameEn().trim().equalsIgnoreCase(nameEn.trim())))
                .findFirst()
                .ifPresent(existing -> {
                    throw ApiException.stateConflict("A service category with this name already exists: "
                            + existing.getNameEn() + " / " + existing.getNameZh());
                });
    }

    /**
     * `cat_library_assistance` from "Library Assistance" — readable in the
     * database and in audit entries, which a random string would not be. The
     * code is only ever an internal handle, so a taken slug (or a name with no
     * ASCII in it at all) just falls back to a unique suffix rather than
     * failing the create.
     */
    private String generateCode(String nameEn) {
        String slug = nameEn.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        // `code` is VARCHAR(32): "cat_" + 21 leaves room for the "_xxxxxx"
        // uniqueness suffix without overflowing the column.
        String candidate = slug.isEmpty() ? null : "cat_" + slug.substring(0, Math.min(slug.length(), 21));
        if (candidate != null && repository.findByCode(candidate).isEmpty()) {
            return candidate;
        }
        String base = candidate != null ? candidate : "cat";
        String unique;
        do {
            unique = base + "_" + OpaqueIds.generate("x").substring(2, 8);
        } while (repository.findByCode(unique).isPresent());
        return unique;
    }

    private ServiceCategoryDto toDto(ServiceCategory category, String resolvedLanguage) {
        return new ServiceCategoryDto(category.getId(), category.getCode(),
                LocalizedNames.pick(resolvedLanguage, category.getNameZh(), category.getNameEn()),
                category.getNameZh(), category.getNameEn(), category.isActive());
    }
}
