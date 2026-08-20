package cn.timebank.project.application;

import cn.timebank.project.application.dto.ProjectCapacitySnapshot;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectGenerationRequest;
import cn.timebank.project.application.dto.ProjectRequests.CancelProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.CreateProjectRequest;
import cn.timebank.project.application.dto.ProjectRequests.UpdateProjectRequest;
import cn.timebank.project.application.dto.TimelineEntryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

/** The `project` module's published interface (Design.md §6). */
public interface ProjectService {

    /**
     * {@code status}, {@code categoryId}, and {@code gradeId} are contract §2.7's
     * {@code filter[...]}, {@code search} matches {@code title} only
     * (organization name is resolved separately, post-query, from another
     * module — see {@code ProjectServiceImpl}); null/blank means
     * unfiltered, an unrecognized {@code status} matches nothing rather
     * than throwing. {@code gradeId} matches if it appears anywhere in the
     * project's {@code eligibleGradeIds} — a project can be eligible for
     * more than one grade, so this is "contains", not "equals".
     */
    Page<ProjectDto> list(Pageable pageable, String status, String categoryId, String gradeId, String search,
                           Authentication auth, String resolvedLanguage);

    ProjectDto getDetail(String id, Authentication auth, String resolvedLanguage);

    /**
     * Unscoped — no visibility/ownership check, same shape as {@code StudentService}'s
     * unscoped {@code getDetail} overload. For cross-module, system-level lookups only
     * (Docs/optimization/03-ServiceRecordFormOptimization.md §3.1 — {@code servicerecord}
     * snapshots a PROJECT-sourced record's organization/owner/category/location from
     * here at creation time), never for anything a caller can reach directly with an
     * arbitrary id.
     */
    ProjectDto getDetail(String id, String resolvedLanguage);

    /** {@code actorIsAdmin}: gates whether {@code request.ownerId()} is honored — anyone else's is silently forced to {@code actorId} (02-PorjectOrganization.md §3). */
    ProjectDto createDraft(CreateProjectRequest request, String actorId, boolean actorIsAdmin, String resolvedLanguage);

    ProjectDto updateDraft(String id, UpdateProjectRequest request, String actorId, boolean actorIsAdmin, String resolvedLanguage);

    ProjectDto publish(String id, Long version, String actorId, String resolvedLanguage);

    /** Rejects with a state-conflict if `status == COMPLETED` and any confirmed student's record isn't yet reviewed — {@link #cancel} is the override. */
    ProjectDto archive(String id, Long version, String actorId, String resolvedLanguage);

    /** The manager override — archives a live (or completed-but-unreviewed) project outright, reason required (02-PorjectOrganization.md §2.1). */
    ProjectDto cancel(String id, CancelProjectRequest request, String actorId, String resolvedLanguage);

    List<TimelineEntryDto> timeline(String id, Authentication auth);

    // --- Consumed by `enrollment`/`proposal` without reaching into this module's domain/infrastructure ---

    /** B4.7: locks the project row for the duration of the caller's transaction (propagation REQUIRED joins it). */
    ProjectCapacitySnapshot lockForRegistration(String projectId);

    /** B4.11: called only from `proposal`'s approval transaction. @return the new project's id. */
    String createFromProposal(ProjectGenerationRequest request);

    /** B4.6: denormalizing `projectTitle` on enrollment lists without N+1 and without an ambient visibility check (titles are not sensitive). */
    Map<String, String> titlesOf(List<String> projectIds);
}
