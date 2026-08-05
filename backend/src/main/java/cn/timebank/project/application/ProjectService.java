package cn.timebank.project.application;

import cn.timebank.project.application.dto.ProjectCapacitySnapshot;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.ProjectGenerationRequest;
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
     * {@code status} and {@code categoryId} are contract §2.7's
     * {@code filter[...]}, {@code search} matches {@code title} only
     * (organization name is resolved separately, post-query, from another
     * module — see {@code ProjectServiceImpl}); null/blank means
     * unfiltered, an unrecognized {@code status} matches nothing rather
     * than throwing.
     */
    Page<ProjectDto> list(Pageable pageable, String status, String categoryId, String search,
                           Authentication auth, String resolvedLanguage);

    ProjectDto getDetail(String id, Authentication auth, String resolvedLanguage);

    ProjectDto createDraft(CreateProjectRequest request, String actorId, String resolvedLanguage);

    ProjectDto updateDraft(String id, UpdateProjectRequest request, String actorId, String resolvedLanguage);

    ProjectDto publish(String id, Long version, String actorId, String resolvedLanguage);

    ProjectDto close(String id, Long version, String actorId, String resolvedLanguage);

    ProjectDto archive(String id, Long version, String actorId, String resolvedLanguage);

    List<TimelineEntryDto> timeline(String id, Authentication auth);

    // --- Consumed by `enrollment`/`proposal` without reaching into this module's domain/infrastructure ---

    /** B4.7: locks the project row for the duration of the caller's transaction (propagation REQUIRED joins it). */
    ProjectCapacitySnapshot lockForRegistration(String projectId);

    /** B4.11: called only from `proposal`'s approval transaction. @return the new project's id. */
    String createFromProposal(ProjectGenerationRequest request);

    /** B4.6: denormalizing `projectTitle` on enrollment lists without N+1 and without an ambient visibility check (titles are not sensitive). */
    Map<String, String> titlesOf(List<String> projectIds);
}
