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

/**
 * Contract §4.10: `/service-categories`.
 *
 * <p>Issue 157 gave these their own permissions. They used to borrow
 * {@code rules.manage}, whose description admitted it — "Create or edit rule
 * drafts <i>and service categories</i>". A rule version is a versioned,
 * immutable-once-published policy object whose change recalculates every
 * student's compliance; a service category is a word on a dropdown. Sharing one
 * permission meant a school could not let someone tidy the category list without
 * also handing them the rules that decide who graduates.</p>
 *
 * <p>Three levels now, and the split is where the information is:</p>
 *
 * <ul>
 *   <li>the plain list — selectable categories, the one behind every form's
 *       picker — stays {@code isAuthenticated()}, because a student submitting a
 *       service record has to have it, and a permission every role must hold
 *       never denies anything;</li>
 *   <li>{@code ?includeInactive=true} needs {@code serviceCategories.read}: it
 *       is the management view, and the only thing it adds is what has been
 *       <i>retired</i>;</li>
 *   <li>the writes need {@code serviceCategories.manage}.</li>
 * </ul>
 */
@RestController
public class ServiceCategoryController {

    private final ServiceCategoryService serviceCategoryService;
    private final RequestLanguageService requestLanguageService;

    public ServiceCategoryController(ServiceCategoryService serviceCategoryService, RequestLanguageService requestLanguageService) {
        this.serviceCategoryService = serviceCategoryService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping("/api/v1/service-categories")
    // `isAuthenticated()` first and always: without it, `!#includeInactive`
    // alone evaluates true for an anonymous caller and the dictionary becomes
    // a public endpoint.
    @PreAuthorize("isAuthenticated() and (!#includeInactive or hasAuthority('serviceCategories.read'))")
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
    @PreAuthorize("hasAuthority('serviceCategories.manage')")
    public ApiResponse<ServiceCategoryDto> create(
            @Valid @RequestBody CreateServiceCategoryRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(serviceCategoryService.create(request, principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @PatchMapping("/api/v1/service-categories/{id}")
    @PreAuthorize("hasAuthority('serviceCategories.manage')")
    public ApiResponse<ServiceCategoryDto> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateServiceCategoryRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(serviceCategoryService.update(id, request, principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @DeleteMapping("/api/v1/service-categories/{id}")
    @PreAuthorize("hasAuthority('serviceCategories.manage')")
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
