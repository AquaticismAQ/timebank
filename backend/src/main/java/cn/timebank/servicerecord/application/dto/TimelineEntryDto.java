package cn.timebank.servicerecord.application.dto;

import java.time.Instant;

/** Contract's business timeline (Design.md §13) — merged from revisions + review decisions, not stored separately. */
public record TimelineEntryDto(String type, String description, String actorId, Instant occurredAt) {
}
