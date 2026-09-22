package cn.timebank.project.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.ProjectTimelineService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectRequests.ActionRequest;
import cn.timebank.project.application.dto.ProjectRequests.CancelProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.UpdateProjectRequest;
import cn.timebank.project.application.dto.TimelineEntryDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.TimeBankPrincipal;
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
    private final ProjectTimelineService projectTimelineService;
    private final RequestLanguageService requestLanguageService;

    public ProjectController(ProjectService projectService, ProjectTimelineService projectTimelineService,
                             RequestLanguageService requestLanguageService) {
        this.projectService = projectService;
        this.projectTimelineService = projectTimelineService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('projects.read')")
    public ApiResponse<List<ProjectDto>> list(
            PageQuery query, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<ProjectDto> page = projectService.list(pageable, query.filter("status"), query.filter("categoryId"),
                query.filter("gradeId"), query.filter("academicYearId"), query.search(),
                auth, resolveLanguage(acceptLanguage, auth));
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
        return ApiResponse.of(projectService.createDraft(request, principalId(auth), isAdmin(auth), resolveLanguage(acceptLanguage, auth)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('projects.update')")
    public ApiResponse<ProjectDto> update(
            @PathVariable String id, @Valid @RequestBody UpdateProjectRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(projectService.updateDraft(id, request, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('projects.publish')")
    public ApiResponse<ProjectDto> publish(
            @PathVariable String id, @Valid @RequestBody ActionRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(projectService.publish(id, request.version(), auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('projects.archive')")
    public ApiResponse<ProjectDto> archive(
            @PathVariable String id, @Valid @RequestBody ActionRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(projectService.archive(id, request.version(), principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    /** The manager override (02-PorjectOrganization.md §2.1) — cancels a live (or completed-but-unreviewed) project outright; reason required. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('projects.cancel')")
    public ApiResponse<ProjectDto> cancel(
            @PathVariable String id, @Valid @RequestBody CancelProjectRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(projectService.cancel(id, request, principalId(auth), resolveLanguage(acceptLanguage, auth)));
    }

    /**
     * The project's whole life, oldest first (issue 158) — created, edited,
     * published, registered for, cancelled, archived, with who and when.
     *
     * <p>Assembled from the audit log rather than a history table of its own:
     * every one of these events is already recorded there, and a second history
     * would be a second thing to keep in step with the first. Unpaged, because a
     * story with a page missing is not a story — see
     * {@code AuditLogQueryService.entriesFor}.</p>
     *
     * <p>Action codes travel raw ({@code project.publish},
     * {@code enrollment.register}); the client supplies the words, as everywhere
     * else in this API.</p>
     */
    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('projects.audit.read')")
    public ApiResponse<List<TimelineEntryDto>> timeline(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(projectTimelineService.timeline(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }

    /**
     * Whether the caller may set `ownerId` to someone other than themselves (02-PorjectOrganization.md §3) —
     * role-based, not `projects.*`-permission-based, since every teacher already holds `projects.create`/`.update`.
     * 07-RoleFinetune.md §2.3: was literal `"admin"` only, so a `sysadmin` account couldn't reassign an owner
     * despite (post role-merge) holding identical permissions — now any non-`student` (staff) role, consistent
     * with {@code ProjectServiceImpl.isManager}.
     */
    private boolean isAdmin(Authentication auth) {
        return ((TimeBankPrincipal) auth.getPrincipal()).hasFullDataScope();
    }
}
