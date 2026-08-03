package cn.timebank.proposal.domain;

import cn.timebank.common.error.ApiException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** B4.9's explicit transition table, copying {@code ServiceRecordStateMachine}'s shape exactly (B3.2's template). */
public final class ProposalStateMachine {

    public enum ActorRole { OWNER, REVIEWER }

    private record Edge(ProposalStatus to, Set<ActorRole> allowedActors) {
    }

    private static final Map<ProposalStatus, Set<Edge>> TRANSITIONS = new EnumMap<>(ProposalStatus.class);

    static {
        rule(ProposalStatus.DRAFT, ProposalStatus.SUBMITTED, ActorRole.OWNER);
        rule(ProposalStatus.SUBMITTED, ProposalStatus.DRAFT, ActorRole.OWNER);
        rule(ProposalStatus.SUBMITTED, ProposalStatus.UNDER_REVIEW, ActorRole.REVIEWER);
        rule(ProposalStatus.UNDER_REVIEW, ProposalStatus.APPROVED, ActorRole.REVIEWER);
        rule(ProposalStatus.UNDER_REVIEW, ProposalStatus.CHANGES_REQUESTED, ActorRole.REVIEWER);
        rule(ProposalStatus.UNDER_REVIEW, ProposalStatus.REJECTED, ActorRole.REVIEWER);
        rule(ProposalStatus.CHANGES_REQUESTED, ProposalStatus.SUBMITTED, ActorRole.OWNER);
    }

    private static void rule(ProposalStatus from, ProposalStatus to, ActorRole... actors) {
        TRANSITIONS.computeIfAbsent(from, k -> new HashSet<>())
                .add(new Edge(to, EnumSet.copyOf(List.of(actors))));
    }

    private ProposalStateMachine() {
    }

    public static boolean canTransition(ProposalStatus from, ProposalStatus to, ActorRole actor) {
        return TRANSITIONS.getOrDefault(from, Set.of()).stream()
                .anyMatch(edge -> edge.to() == to && edge.allowedActors().contains(actor));
    }

    public static void transition(EventProposal proposal, ProposalStatus to, ActorRole actor) {
        if (!canTransition(proposal.getStatus(), to, actor)) {
            throw ApiException.stateConflict(
                    "Cannot move from " + proposal.getStatus() + " to " + to + " as " + actor + ".");
        }
        proposal.applyStatus(to);
    }
}
