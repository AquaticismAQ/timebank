package cn.timebank.project.application.dto;

import java.time.Instant;

public record TimelineEntryDto(String type, String description, String actorId, Instant occurredAt) {
}
