package cn.timebank.servicerecord.domain;

import cn.timebank.common.error.ApiException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B3.2: the explicit transition table {@code ServiceRecord} uses instead of
 * scattered {@code if (status == ...)} checks — later phases (Project,
 * EventProposal, Application, Case) copy this shape for their own state
 * machines (Design.md §12). {@link #transition} is the single entry point;
 * {@link ServiceRecord#applyStatus} is package-private specifically so no
 * code outside this class can change status any other way.
 *
 * {@code withdraw} (contract §4.5) has no explicit target status in
 * contract §3.4's diagram — it is implemented here as reverting
 * {@code SUBMITTED} back to {@code DRAFT}, since "review not started" is
 * exactly the precondition contract §4.5 states for withdrawal, and a
 * student who withdraws should be able to edit and resubmit.
 */
public final class ServiceRecordStateMachine {

    public enum ActorRole { OWNER, REVIEWER, SYSTEM }

    private record Edge(ServiceRecordStatus to, Set<ActorRole> allowedActors) {
    }

    private static final Map<ServiceRecordStatus, Set<Edge>> TRANSITIONS = new EnumMap<>(ServiceRecordStatus.class);

    static {
        rule(ServiceRecordStatus.DRAFT, ServiceRecordStatus.SUBMITTED, ActorRole.OWNER);
        rule(ServiceRecordStatus.SUBMITTED, ServiceRecordStatus.DRAFT, ActorRole.OWNER);
        rule(ServiceRecordStatus.SUBMITTED, ServiceRecordStatus.UNDER_REVIEW, ActorRole.REVIEWER);
        rule(ServiceRecordStatus.UNDER_REVIEW, ServiceRecordStatus.APPROVED, ActorRole.REVIEWER);
        rule(ServiceRecordStatus.UNDER_REVIEW, ServiceRecordStatus.RETURNED, ActorRole.REVIEWER);
        rule(ServiceRecordStatus.UNDER_REVIEW, ServiceRecordStatus.REJECTED, ActorRole.REVIEWER);
        rule(ServiceRecordStatus.RETURNED, ServiceRecordStatus.SUBMITTED, ActorRole.OWNER);
        rule(ServiceRecordStatus.REJECTED, ServiceRecordStatus.APPEAL_PENDING, ActorRole.OWNER, ActorRole.SYSTEM);
        rule(ServiceRecordStatus.APPROVED, ServiceRecordStatus.CORRECTED, ActorRole.REVIEWER, ActorRole.SYSTEM);
    }

    private static void rule(ServiceRecordStatus from, ServiceRecordStatus to, ActorRole... actors) {
        TRANSITIONS.computeIfAbsent(from, k -> new HashSet<>())
                .add(new Edge(to, EnumSet.copyOf(List.of(actors))));
    }

    private ServiceRecordStateMachine() {
    }

    public static boolean canTransition(ServiceRecordStatus from, ServiceRecordStatus to, ActorRole actor) {
        return TRANSITIONS.getOrDefault(from, Set.of()).stream()
                .anyMatch(edge -> edge.to() == to && edge.allowedActors().contains(actor));
    }

    public static void transition(ServiceRecord record, ServiceRecordStatus to, ActorRole actor) {
        if (!canTransition(record.getStatus(), to, actor)) {
            throw ApiException.stateConflict(
                    "Cannot move from " + record.getStatus() + " to " + to + " as " + actor + ".");
        }
        record.applyStatus(to);
    }
}
