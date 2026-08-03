/**
 * Student event proposals, approval chain, and the atomic project-generation
 * transaction (B4.9-B4.12, invariant 6): approving a proposal writes the
 * approval event, sets `approved`, generates the official {@code Project}
 * draft, backfills `approvedProjectId`, and notifies — all in one
 * transaction ({@link cn.timebank.proposal.application.ProposalApprovalTransaction}).
 * An `approved` proposal without a linked project cannot exist.
 */
package cn.timebank.proposal;
