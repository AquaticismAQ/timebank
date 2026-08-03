package cn.timebank.project.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectRequests.ActionRequest;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.UpdateProjectRequest;
import cn.timebank.project.application.dto.TimelineEntryDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Contract §4.2. */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private static final Set<String> SORTABLE = Set.of("id", "startAt", "registrationOpenAt", "status", "createdAt");

    private final ProjectService projectService;
    private final RequestLanguageService requestLanguageService;

    public ProjectController(ProjectService projectService, RequestLanguageService requestLanguageService) {
        this.projectService = projectService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('projects.read')")
    public ApiResponse<List<ProjectDto>> list(
            PageQuery query, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<ProjectDto> page = projectService.list(pageable, auth, resolveLanguage(acceptLanguage, auth));
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('projects.read')")
    public ApiResponse<ProjectDto> get(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(projectService.getDetail(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('projects.create')")
    public ApiResponse<ProjectDto> create(
            @Valid @RequestBody CreateProjectRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(projectService.createDraft(request, principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('projects.update')")
    public ApiResponse<ProjectDto> update(
            @PathVariable String id, @Valid @RequestBody UpdateProjectRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(projectService.updateDraft(id, request, principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('projects.publish')")
    public ApiResponse<ProjectDto> publish(
            @PathVariable String id, @Valid @RequestBody ActionRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(projectService.publish(id, request.version(), principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('projects.close')")
    public ApiResponse<ProjectDto> close(
            @PathVariable String id, @Valid @RequestBody ActionRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(projectService.close(id, request.version(), principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('projects.archive')")
    public ApiResponse<ProjectDto> archive(
            @PathVariable String id, @Valid @RequestBody ActionRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(projectService.archive(id, request.version(), principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('projects.audit.read')")
    public ApiResponse<List<TimelineEntryDto>> timeline(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(projectService.timeline(id, auth));
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
