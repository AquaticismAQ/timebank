package cn.timebank.servicerecord.application.dto;

import java.time.Instant;

public record RevisionDto(String id, int revisionNumber, String snapshotJson, String editedBy, Instant createdAt) {
}
