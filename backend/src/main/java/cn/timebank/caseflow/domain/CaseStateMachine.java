package cn.timebank.caseflow.domain;

import cn.timebank.common.error.ApiException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** B5.1's explicit transition table, copying {@code ProposalStateMachine}'s shape exactly (B3.2's template). */
public final class CaseStateMachine {

    public enum ActorRole { OWNER, REVIEWER }

    private record Edge(CaseStatus to, Set<ActorRole> allowedActors) {
    }

    private static final Map<CaseStatus, Set<Edge>> TRANSITIONS = new EnumMap<>(CaseStatus.class);

    static {
        rule(CaseStatus.DRAFT, CaseStatus.SUBMITTED, ActorRole.OWNER);
        // Withdraw (issue 081), mirroring the service record's own edge: there is
        // deliberately no edge out of NEEDS_INFORMATION or any decided status, so
        // "only before a decision" is enforced by the table, not a separate check.
        rule(CaseStatus.SUBMITTED, CaseStatus.DRAFT, ActorRole.OWNER);
        // A decision is one step (issue 082): the IN_REVIEW hop these edges used
        // to route through was never committed, and nothing else could put a case
        // there — cases never had a claim endpoint at all.
        rule(CaseStatus.SUBMITTED, CaseStatus.RESOLVED, ActorRole.REVIEWER);
        rule(CaseStatus.SUBMITTED, CaseStatus.NEEDS_INFORMATION, ActorRole.REVIEWER);
        rule(CaseStatus.SUBMITTED, CaseStatus.REJECTED, ActorRole.REVIEWER);
        rule(CaseStatus.NEEDS_INFORMATION, CaseStatus.SUBMITTED, ActorRole.OWNER);
    }

    private static void rule(CaseStatus from, CaseStatus to, ActorRole... actors) {
        TRANSITIONS.computeIfAbsent(from, k -> new HashSet<>())
                .add(new Edge(to, EnumSet.copyOf(List.of(actors))));
    }

    private CaseStateMachine() {
    }

    public static boolean canTransition(CaseStatus from, CaseStatus to, ActorRole actor) {
        return TRANSITIONS.getOrDefault(from, Set.of()).stream()
                .anyMatch(edge -> edge.to() == to && edge.allowedActors().contains(actor));
    }

    public static void transition(Case c, CaseStatus to, ActorRole actor) {
        if (!canTransition(c.getStatus(), to, actor)) {
            throw ApiException.stateConflict(
                    "Cannot move from " + c.getStatus() + " to " + to + " as " + actor + ".");
        }
        c.applyStatus(to);
    }
}
