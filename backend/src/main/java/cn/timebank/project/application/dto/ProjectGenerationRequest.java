package cn.timebank.project.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * B4.11: what {@code proposal}'s approval transaction passes to {@code
 * ProjectService.createFromProposal} — a DTO crossing the module boundary,
 * never the {@code EventProposal} entity. Registration defaults to opening
 * immediately and closing at the activity's own start time; the owner can
 * adjust both via `PATCH` before publishing (the generated project still
 * requires a separate publish action, contract §4.3).
 */
public record ProjectGenerationRequest(
        String title, String summary, String description, String categoryId, String organizationId,
        String ownerId, String originProposalId, Instant startAt, Instant endAt, String location,
        int capacity, int recognizedMinutes, String evidenceRequirements, List<String> studentOrganizerIds) {
}
