package cn.timebank.organization.application;

import cn.timebank.organization.application.dto.ServiceCategoryDto;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Contract §4.10: `GET/POST/PATCH /service-categories`, behind `rules.manage` for writes. */
public interface ServiceCategoryService {

    /**
     * Every category, active or not — other modules resolve the display name of
     * records that already reference a retired category through this.
     */
    List<ServiceCategoryDto> list(String resolvedLanguage);

    /**
     * What a picker should offer (issue 078): retiring a category by clearing
     * `active` has to actually stop it appearing in new records, otherwise the
     * flag means nothing and delete would be the only way to retire anything.
     */
    default List<ServiceCategoryDto> listSelectable(String resolvedLanguage) {
        return list(resolvedLanguage).stream().filter(ServiceCategoryDto::active).toList();
    }

    ServiceCategoryDto create(CreateServiceCategoryRequest request, String actorId, String resolvedLanguage);

    ServiceCategoryDto update(String id, UpdateServiceCategoryRequest request, String actorId, String resolvedLanguage);

    void delete(String id, String actorId);

    /**
     * No `code`: nothing outside this table needs a human-chosen one (records
     * reference the opaque id), so the caller supplies only what it can
     * meaningfully choose and the service derives the code (issue 078).
     */
    record CreateServiceCategoryRequest(@NotBlank String nameZh, @NotBlank String nameEn) {
    }

    record UpdateServiceCategoryRequest(String nameZh, String nameEn, Boolean active) {
    }
}
