package cn.timebank.project.application;

import cn.timebank.audit.application.AuditLogQueryService;
import cn.timebank.audit.application.dto.AuditLogDto;
import cn.timebank.enrollment.application.EnrollmentService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.project.application.dto.TimelineEntryDto;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <h2>The bridge to registrations and service records</h2>
 *
 * <p>An enrollment's audit rows are recorded against the <b>enrollment</b>, and
 * a service record's against the <b>record</b> — and nothing on either names the
 * project. So "the registration and record events of project X" is not a query
 * the audit log can answer alone: the two owning tables are the only link, and
 * they are asked through {@link EnrollmentService} and
 * {@link ServiceRecordService} rather than by reaching into another module's
 * tables.</p>
 *
 * <p>Both bridges return the <b>student's name alongside the id</b>, because the
 * actor on a confirmation or a review decision is the staff member: without it,
 * "Record approved — Teacher Wei Chen" never says whose record.</p>
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
    private static final String SERVICE_RECORD_RESOURCE = "serviceRecord";

    /**
     * Synthesized when the log has no create event for a project, so a timeline
     * always has a beginning. Seeded projects are the case that needs it: they
     * were inserted directly, never through {@code POST /projects}, so no
     * {@code project.create} row exists and the story would otherwise open in
     * the middle.
     */
    private static final String SYNTHETIC_CREATE = "project.created";

    private static final List<String> CREATE_ACTIONS = List.of("project.create", "project.generatedFromProposal");

    /**
     * The same idea for a registration the log never recorded.
     *
     * <p>The seeded dataset inserts confirmed enrollments with direct SQL
     * (`integration-test/seed.ts` Phase 3.5), because registering through the API
     * needs the student's own session and an open window, which a project seeded
     * straight into {@code COMPLETED} does not have. Those registrations are
     * real rows — they simply never passed through the application, so no
     * {@code enrollment.register} entry exists, and a timeline built only from
     * the log showed service records appearing for students who had apparently
     * never signed up.</p>
     *
     * <p>Derived from {@code enrollment.applied_at}, which is a recorded fact
     * about when the student registered — not a guess. A distinct code from the
     * audited {@code enrollment.register} so provenance stays legible to anyone
     * reading the DTO, while the client gives both the same words.</p>
     */
    private static final String SYNTHETIC_REGISTER = "enrollment.registered";

    private static final String AUDITED_REGISTER = "enrollment.register";

    /**
     * Service-record events that belong to the student's own drafting, not to
     * the project's story.
     *
     * <p>A record is written, edited and re-edited privately before anyone else
     * can see it — on real data that is three or four rows per record, and a
     * project with six records drowned its four actual events in twenty lines of
     * somebody typing. The project's history starts when the student
     * <b>submits</b>; what happened in the notebook first is between them and
     * the draft.</p>
     *
     * <p>A denylist rather than an allowlist, deliberately: a service-record
     * action added later should appear here unbidden. Showing an unexpected real
     * event is a much smaller failure than silently hiding one.</p>
     */
    /**
     * Time-derived transitions, which no audit row records.
     *
     * <p>{@code syncTimeBasedStatus} advances a project as its dates pass and
     * writes nothing to the log — correctly, since nobody did anything. But two
     * of those moments are part of the story a reader is reconstructing:
     * <b>registration opened</b>, and the project <b>finishing</b>. Both are
     * derived from the project's own columns, which is where the fact already
     * lives, and both are marked system-generated: the backend did them, and
     * saying so is more honest than a blank.</p>
     *
     * <p>The other two transitions — registration closing, the run starting —
     * are deliberately absent. Every date on the card would become a row, and a
     * timeline that narrates the passage of time stops being about what anyone
     * did.</p>
     */
    private static final String SYNTHETIC_REGISTRATION_OPENED = "project.registrationOpened";

    private static final String SYNTHETIC_COMPLETED = "project.completed";

    /** Actions the backend performs on a schedule, so they read as the system's rather than nobody's. */
    private static final Set<String> SYSTEM_ACTIONS = Set.of("project.autoArchiveNoRegistrants");

    private static final Set<String> DRAFTING_ACTIONS = Set.of(
            "serviceRecord.create", "serviceRecord.update", "serviceRecord.delete");

    private final ProjectService projectService;
    private final EnrollmentService enrollmentService;
    private final ServiceRecordService serviceRecordService;
    private final AuditLogQueryService auditLogQueryService;

    ProjectTimelineService(ProjectService projectService, EnrollmentService enrollmentService,
                           ServiceRecordService serviceRecordService, AuditLogQueryService auditLogQueryService) {
        this.projectService = projectService;
        this.enrollmentService = enrollmentService;
        this.serviceRecordService = serviceRecordService;
        this.auditLogQueryService = auditLogQueryService;
    }

    @Transactional(readOnly = true)
    public List<TimelineEntryDto> timeline(String projectId, Authentication auth, String resolvedLanguage) {
        // Existence and data scope in one call, the same read the detail page
        // makes: a teacher cannot read the history of a project outside their
        // grades, and an out-of-scope id 404s rather than reporting who exists.
        ProjectDto project = projectService.getDetail(projectId, auth, resolvedLanguage);

        // Three sources, one story. The project's own rows are about the
        // project; the other two are about a person, and carry whose.
        List<EnrollmentService.EnrollmentSubject> registrants =
                enrollmentService.enrollmentSubjectsOf(projectId, resolvedLanguage);
        Map<String, String> registrantNames = registrants.stream().collect(Collectors.toMap(
                EnrollmentService.EnrollmentSubject::enrollmentId,
                EnrollmentService.EnrollmentSubject::studentName, (a, b) -> a, LinkedHashMap::new));
        Map<String, String> records = serviceRecordService.recordSubjectsOfProject(projectId);

        List<AuditLogDto> enrollmentRows = auditLogQueryService.entriesFor(ENROLLMENT_RESOURCE, registrantNames.keySet());

        List<TimelineEntryDto> entries = new ArrayList<>();
        entries.addAll(mapped(auditLogQueryService.entriesFor(PROJECT_RESOURCE, List.of(projectId)), Map.of()));
        entries.addAll(mapped(enrollmentRows, registrantNames));
        entries.addAll(mapped(auditLogQueryService.entriesFor(SERVICE_RECORD_RESOURCE, records.keySet()).stream()
                .filter(row -> !DRAFTING_ACTIONS.contains(row.action()))
                .toList(), records));

        if (entries.stream().noneMatch(e -> CREATE_ACTIONS.contains(e.action()))) {
            entries.add(new TimelineEntryDto(SYNTHETIC_CREATE, project.ownerId(), null, null, null,
                    project.createdAt(), false));
        }
        entries.addAll(derivedRegistrations(registrants, enrollmentRows));
        entries.addAll(derivedStatusMoments(project));

        // Oldest first: a life story reads forwards. The id is not available to
        // break ties across the two reads, so equal timestamps keep whatever
        // order they arrived in — a stable sort, not an arbitrary one.
        entries.sort(Comparator.comparing(TimelineEntryDto::occurredAt));
        return entries;
    }

    /**
     * A "registered" entry for each enrollment the audit log has no record of
     * signing up — see {@link #SYNTHETIC_REGISTER}.
     *
     * <p>Keyed on the enrollment, not on a global flag: one project can have both
     * seeded and real registrations, and adding a derived line for a student the
     * log already covers would show their registration twice.</p>
     */
    private static List<TimelineEntryDto> derivedRegistrations(
            List<EnrollmentService.EnrollmentSubject> registrants, List<AuditLogDto> enrollmentRows) {
        Set<String> audited = enrollmentRows.stream()
                .filter(row -> AUDITED_REGISTER.equals(row.action()))
                .map(AuditLogDto::resourceId)
                .collect(Collectors.toSet());
        return registrants.stream()
                .filter(r -> !audited.contains(r.enrollmentId()) && r.appliedAt() != null)
                .map(r -> new TimelineEntryDto(SYNTHETIC_REGISTER, null, null,
                        subjectOrNull(r.studentName()), r.status(), r.appliedAt(), false))
                .toList();
    }

    /**
     * The two time-derived moments worth a row — see
     * {@link #SYNTHETIC_REGISTRATION_OPENED}.
     *
     * <p>Each is added only once it has actually happened. A window that opens
     * next week has not opened, and a project still running has not finished;
     * printing either would turn the history into a schedule.</p>
     */
    private static List<TimelineEntryDto> derivedStatusMoments(ProjectDto project) {
        List<TimelineEntryDto> moments = new ArrayList<>();
        Instant now = Instant.now();

        // A draft was never published, so its window never opened however its
        // dates read.
        boolean published = !"DRAFT".equals(project.status());
        if (published && project.registrationOpenAt() != null && !project.registrationOpenAt().isAfter(now)) {
            moments.add(new TimelineEntryDto(SYNTHETIC_REGISTRATION_OPENED, null, null, null, null,
                    project.registrationOpenAt(), true));
        }

        // Only COMPLETED. An ARCHIVED project may have been cancelled rather
        // than finished, and the log cannot always tell the two apart — calling
        // a cancelled project "completed" would be a worse error than omitting
        // the line.
        if ("COMPLETED".equals(project.status()) && project.endAt() != null && !project.endAt().isAfter(now)) {
            moments.add(new TimelineEntryDto(SYNTHETIC_COMPLETED, null, null, null, null, project.endAt(), true));
        }
        return moments;
    }

    private List<TimelineEntryDto> mapped(List<AuditLogDto> rows, Map<String, String> subjects) {
        return rows.stream()
                .map(row -> new TimelineEntryDto(row.action(), row.actorId(), row.actorDisplayName(),
                        subjectOrNull(subjects.get(row.resourceId())), detailOf(row), row.createdAt(),
                        SYSTEM_ACTIONS.contains(row.action())))
                .toList();
    }

    /** A blank name is no name: a deleted student should read as absent, not as an empty column. */
    private static String subjectOrNull(String name) {
        return name == null || name.isBlank() ? null : name;
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
