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
 * phases). {@code REGISTRATION_OPEN}/{@code IN_PROGRESS}/{@code COMPLETED}
 * are time-derived (actor {@code SYSTEM}) — synced lazily on read/action
 * access (`ProjectServiceImpl.syncTimeBasedStatus`) rather than by a
 * scheduled job, since no ShedLock/cron infrastructure exists yet in this
 * codebase; see `Docs/implementation/00-Status.md` §16 for that scope note.
 */
public final class ProjectStateMachine {

    public enum ActorRole { MANAGER, SYSTEM }

    private record Edge(ProjectStatus to, Set<ActorRole> allowedActors) {
    }

    private static final Map<ProjectStatus, Set<Edge>> TRANSITIONS = new EnumMap<>(ProjectStatus.class);

    static {
        rule(ProjectStatus.DRAFT, ProjectStatus.PUBLISHED, ActorRole.MANAGER);
        rule(ProjectStatus.PUBLISHED, ProjectStatus.REGISTRATION_OPEN, ActorRole.SYSTEM);
        rule(ProjectStatus.REGISTRATION_OPEN, ProjectStatus.IN_PROGRESS, ActorRole.SYSTEM);
        rule(ProjectStatus.IN_PROGRESS, ProjectStatus.COMPLETED, ActorRole.SYSTEM);
        rule(ProjectStatus.PUBLISHED, ProjectStatus.CLOSED, ActorRole.MANAGER);
        rule(ProjectStatus.REGISTRATION_OPEN, ProjectStatus.CLOSED, ActorRole.MANAGER);
        rule(ProjectStatus.IN_PROGRESS, ProjectStatus.CLOSED, ActorRole.MANAGER);
        rule(ProjectStatus.COMPLETED, ProjectStatus.ARCHIVED, ActorRole.MANAGER);
        rule(ProjectStatus.CLOSED, ProjectStatus.ARCHIVED, ActorRole.MANAGER);
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
}
