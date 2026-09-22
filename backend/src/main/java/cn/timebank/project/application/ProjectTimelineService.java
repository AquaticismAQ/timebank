package cn.timebank.project.application;

import cn.timebank.audit.application.AuditLogQueryService;
import cn.timebank.audit.application.dto.AuditLogDto;
import cn.timebank.enrollment.application.EnrollmentService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.project.application.dto.TimelineEntryDto;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything that happened to one project, assembled from the audit log
 * (issue 158).
 *
 * <p>{@code projects.audit.read} has existed in the permission catalogue since
 * B1 and {@code GET /projects/{id}/timeline} since B3, but the endpoint returned
 * a stub: a synthetic "created" line plus one row per {@code project_revision},
 * with raw user ids and two hardcoded English sentences. It knew nothing about
 * publishing, archiving, cancelling, or any registration — so the one question a
 * timeline exists to answer, *who did what to this project and when*, it could
 * not answer.</p>
 *
 * <h2>Why the audit log and not a table of its own</h2>
 *
 * <p>Every one of these events is already recorded, by the append-only audit log
 * that exists precisely so that no action is deniable. A second history would be
 * a second thing to keep in step with the first, and the moment they disagreed
 * neither would be trustworthy. This reads the record that already exists.</p>
 *
 * <h2>The bridge to registrations</h2>
 *
 * <p>An enrollment's audit rows are recorded against the <b>enrollment</b>
 * ({@code resource_type='enrollment'}), and nothing on those rows names the
 * project. So "the registration events of project X" is not a query the audit
 * log can answer alone: the enrollment table is the only link, and it is asked
 * through {@link EnrollmentService} rather than by reaching into another module's
 * tables.</p>
 *
 * <p>This is also why nothing needed a migration or a backfill — the join works
 * on every audit row already written.</p>
 *
 * <h2>Why this is not a method on ProjectServiceImpl</h2>
 *
 * <p>{@code EnrollmentServiceImpl} injects {@link ProjectService}. A project-module
 * service that both injected {@link EnrollmentService} and was injected by
 * {@code ProjectServiceImpl} would close a bean cycle and the application would
 * not start. Sitting beside {@code ProjectService} and called straight from the
 * controller keeps the graph a graph, the same shape
 * {@code StudentReminderService} uses for the same reason.</p>
 */
@Service
public class ProjectTimelineService {

    private static final String PROJECT_RESOURCE = "project";
    private static final String ENROLLMENT_RESOURCE = "enrollment";

    /**
     * Synthesized when the log has no create event for a project, so a timeline
     * always has a beginning. Seeded projects are the case that needs it: they
     * were inserted directly, never through {@code POST /projects}, so no
     * {@code project.create} row exists and the story would otherwise open in
     * the middle.
     */
    private static final String SYNTHETIC_CREATE = "project.created";

    private static final List<String> CREATE_ACTIONS = List.of("project.create", "project.generatedFromProposal");

    private final ProjectService projectService;
    private final EnrollmentService enrollmentService;
    private final AuditLogQueryService auditLogQueryService;

    ProjectTimelineService(ProjectService projectService, EnrollmentService enrollmentService,
                           AuditLogQueryService auditLogQueryService) {
        this.projectService = projectService;
        this.enrollmentService = enrollmentService;
        this.auditLogQueryService = auditLogQueryService;
    }

    @Transactional(readOnly = true)
    public List<TimelineEntryDto> timeline(String projectId, Authentication auth, String resolvedLanguage) {
        // Existence and data scope in one call, the same read the detail page
        // makes: a teacher cannot read the history of a project outside their
        // grades, and an out-of-scope id 404s rather than reporting who exists.
        ProjectDto project = projectService.getDetail(projectId, auth, resolvedLanguage);

        List<TimelineEntryDto> entries = new ArrayList<>();
        entries.addAll(mapped(auditLogQueryService.entriesFor(PROJECT_RESOURCE, List.of(projectId))));
        entries.addAll(mapped(auditLogQueryService.entriesFor(
                ENROLLMENT_RESOURCE, enrollmentService.enrollmentIdsOf(projectId))));

        if (entries.stream().noneMatch(e -> CREATE_ACTIONS.contains(e.action()))) {
            entries.add(new TimelineEntryDto(SYNTHETIC_CREATE, project.ownerId(), null, null, project.createdAt()));
        }

        // Oldest first: a life story reads forwards. The id is not available to
        // break ties across the two reads, so equal timestamps keep whatever
        // order they arrived in — a stable sort, not an arbitrary one.
        entries.sort(Comparator.comparing(TimelineEntryDto::occurredAt));
        return entries;
    }

    private List<TimelineEntryDto> mapped(List<AuditLogDto> rows) {
        return rows.stream()
                .map(row -> new TimelineEntryDto(row.action(), row.actorId(), row.actorDisplayName(),
                        detailOf(row), row.createdAt()))
                .toList();
    }

    /**
     * The one extra fact worth a column, when the row has one.
     *
     * <p>{@code reasonCode} first because it is the deliberate one — a
     * cancellation's required reason, an import's rejection cause — and the
     * {@code after} summary second, which for a registration is the status it
     * landed in. Deliberately not the {@code before} summary: a compact table is
     * not a diff view, and anyone who needs the full change has the Audit Logs
     * screen.</p>
     */
    private static String detailOf(AuditLogDto row) {
        if (row.reasonCode() != null && !row.reasonCode().isBlank()) {
            return row.reasonCode();
        }
        return unquoted(row.afterSummary());
    }

    /**
     * {@code afterSummary} is compact JSON, so a bare status arrives as
     * {@code "CONFIRMED"} — quotes and all — and printing it that way in a table
     * cell looks like a bug rather than a value. Only the outermost pair is
     * stripped, and only when the whole string is one quoted scalar: an object
     * or array summary is left exactly as stored, since trimming those would
     * corrupt them.
     */
    private static String unquoted(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String trimmed = summary.trim();
        boolean quotedScalar = trimmed.length() >= 2
                && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                && trimmed.indexOf('"', 1) == trimmed.length() - 1;
        return quotedScalar ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
    }
}
