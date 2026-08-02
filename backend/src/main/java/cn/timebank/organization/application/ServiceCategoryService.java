package cn.timebank.organization.application;

import cn.timebank.organization.application.dto.ServiceCategoryDto;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Contract §4.10: `GET/POST/PATCH /service-categories`, behind `rules.manage` for writes. */
public interface ServiceCategoryService {

    List<ServiceCategoryDto> list(String resolvedLanguage);

    ServiceCategoryDto create(CreateServiceCategoryRequest request, String actorId, String resolvedLanguage);

    ServiceCategoryDto update(String id, UpdateServiceCategoryRequest request, String actorId, String resolvedLanguage);

    record CreateServiceCategoryRequest(@NotBlank String code, @NotBlank String nameZh, @NotBlank String nameEn) {
    }

    record UpdateServiceCategoryRequest(String nameZh, String nameEn, Boolean active) {
    }
}
