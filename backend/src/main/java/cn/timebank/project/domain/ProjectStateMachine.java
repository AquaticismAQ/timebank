package cn.timebank.project.domain;

import cn.timebank.common.error.ApiException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B4.2's explicit transition table, copying {@code
 * ServiceRecordStateMachine}'s shape exactly (B3.2's template for later
 * phases). {@code REGISTRATION_OPEN}/{@code CLOSED}/{@code IN_PROGRESS}/
 * {@code COMPLETED} are all time-derived (actor {@code SYSTEM}) — synced
 * lazily on read/action access (`ProjectServiceImpl.syncTimeBasedStatus`)
 * rather than by a scheduled job, since no ShedLock/cron infrastructure
 * exists yet in this codebase; see `Docs/implementation/00-Status.md` §16
 * for that scope note.
 *
 * {@code CLOSED} means exactly one thing — the registration window has
 * elapsed — never a manager-initiated cancellation (that used to share this
 * status; see {@code Docs/optimization/02-PorjectOrganization.md} §2 for
 * why it was split out). Cancelling a live project is {@link #cancel}, a
 * deliberately separate path from this class's normal edge table — routing
 * it through {@link #transition} instead would make it reachable from the
 * ordinary (ungated, no-reason-required) {@code archive()} action too,
 * which defeats the point of requiring a reason for cancellation.
 */
public final class ProjectStateMachine {

    public enum ActorRole { MANAGER, SYSTEM }

    private record Edge(ProjectStatus to, Set<ActorRole> allowedActors) {
    }

    private static final Map<ProjectStatus, Set<Edge>> TRANSITIONS = new EnumMap<>(ProjectStatus.class);

    /** Sources {@link #cancel} may archive from — everything except ARCHIVED itself (a manager can discard an unwanted draft the same way as abandoning a live project). */
    private static final Set<ProjectStatus> CANCELLABLE_FROM = EnumSet.of(
            ProjectStatus.DRAFT, ProjectStatus.PUBLISHED, ProjectStatus.REGISTRATION_OPEN, ProjectStatus.CLOSED,
            ProjectStatus.IN_PROGRESS, ProjectStatus.COMPLETED);

    static {
        rule(ProjectStatus.DRAFT, ProjectStatus.PUBLISHED, ActorRole.MANAGER);
        rule(ProjectStatus.PUBLISHED, ProjectStatus.REGISTRATION_OPEN, ActorRole.SYSTEM);
        rule(ProjectStatus.REGISTRATION_OPEN, ProjectStatus.CLOSED, ActorRole.SYSTEM);
        // A project whose registration window closes with zero confirmed
        // registrants skips CLOSED/IN_PROGRESS entirely and self-archives
        // (ProjectServiceImpl.syncTimeBasedStatus picks between this edge and
        // the one above based on the confirmed-enrollment count) — a distinct
        // SYSTEM edge, not routed through cancel(), so it stays logged with
        // its own specific reason rather than a generic manager cancellation.
        rule(ProjectStatus.REGISTRATION_OPEN, ProjectStatus.ARCHIVED, ActorRole.SYSTEM);
        rule(ProjectStatus.CLOSED, ProjectStatus.IN_PROGRESS, ActorRole.SYSTEM);
        rule(ProjectStatus.IN_PROGRESS, ProjectStatus.COMPLETED, ActorRole.SYSTEM);
        rule(ProjectStatus.COMPLETED, ProjectStatus.ARCHIVED, ActorRole.MANAGER);
    }

    private static void rule(ProjectStatus from, ProjectStatus to, ActorRole... actors) {
        TRANSITIONS.computeIfAbsent(from, k -> new HashSet<>())
                .add(new Edge(to, EnumSet.copyOf(List.of(actors))));
    }

    private ProjectStateMachine() {
    }

    public static boolean canTransition(ProjectStatus from, ProjectStatus to, ActorRole actor) {
        return TRANSITIONS.getOrDefault(from, Set.of()).stream()
                .anyMatch(edge -> edge.to() == to && edge.allowedActors().contains(actor));
    }

    public static void transition(Project project, ProjectStatus to, ActorRole actor) {
        if (!canTransition(project.getStatus(), to, actor)) {
            throw ApiException.stateConflict(
                    "Cannot move from " + project.getStatus() + " to " + to + " as " + actor + ".");
        }
        project.applyStatus(to);
    }

    /** The manager override: archives a live project outright, bypassing {@link #transition}'s edge table and the reviewed-records gate {@code ProjectServiceImpl.archive} applies from {@code COMPLETED}. */
    public static void cancel(Project project) {
        if (!CANCELLABLE_FROM.contains(project.getStatus())) {
            throw ApiException.stateConflict("Cannot cancel a project in status " + project.getStatus() + ".");
        }
        project.applyStatus(ProjectStatus.ARCHIVED);
    }
}
