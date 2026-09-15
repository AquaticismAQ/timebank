package cn.timebank.organization.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.organization.application.ServiceCategoryService;
import cn.timebank.organization.application.ServiceCategoryService.CreateServiceCategoryRequest;
import cn.timebank.organization.application.ServiceCategoryService.UpdateServiceCategoryRequest;
import cn.timebank.organization.application.dto.ServiceCategoryDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Contract §4.10: `/service-categories` — read for any authenticated user, writes behind `rules.manage`. */
@RestController
public class ServiceCategoryController {

    private final ServiceCategoryService serviceCategoryService;
    private final RequestLanguageService requestLanguageService;

    public ServiceCategoryController(ServiceCategoryService serviceCategoryService, RequestLanguageService requestLanguageService) {
        this.serviceCategoryService = serviceCategoryService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping("/api/v1/service-categories")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ServiceCategoryDto>> list(
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        // Default: selectable categories only. `includeInactive=true` is for the
        // System Admin management screen, which has to show what it can re-activate.
        String language = resolveLanguage(acceptLanguage, auth);
        return ApiResponse.of(includeInactive
                ? serviceCategoryService.list(language)
                : serviceCategoryService.listSelectable(language));
    }

    @PostMapping("/api/v1/service-categories")
    @PreAuthorize("hasAuthority('rules.manage')")
    public ApiResponse<ServiceCategoryDto> create(
            @Valid @RequestBody CreateServiceCategoryRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(serviceCategoryService.create(request, principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @PatchMapping("/api/v1/service-categories/{id}")
    @PreAuthorize("hasAuthority('rules.manage')")
    public ApiResponse<ServiceCategoryDto> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateServiceCategoryRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(serviceCategoryService.update(id, request, principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @DeleteMapping("/api/v1/service-categories/{id}")
    @PreAuthorize("hasAuthority('rules.manage')")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        serviceCategoryService.delete(id, principalId(auth));
        return ApiResponse.of(null);
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
