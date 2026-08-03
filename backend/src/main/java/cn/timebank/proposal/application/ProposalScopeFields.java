package cn.timebank.proposal.application;

import cn.timebank.proposal.domain.EventProposal;
import cn.timebank.security.application.ScopeFields;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

/** B1.7's mechanism, wired to `eventProposals` — the exit criterion "teacher data scope applies to the proposal approval queue" (B4). */
class ProposalScopeFields implements ScopeFields<EventProposal> {

    @Override
    public Path<String> ownerIdPath(Root<EventProposal> root) {
        return root.get("proposerStudentId");
    }

    @Override
    public Path<String> gradeIdPath(Root<EventProposal> root) {
        return root.get("gradeId");
    }

    @Override
    public Path<String> classIdPath(Root<EventProposal> root) {
        return root.get("classId");
    }

    @Override
    public Path<String> projectIdPath(Root<EventProposal> root) {
        throw new UnsupportedOperationException("Proposals are not project-scoped.");
    }
}
