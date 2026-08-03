package cn.timebank.file.application.dto;

import java.time.Instant;

/** {@code downloadUrl} is only present once {@code status == READY} — nothing to download otherwise. */
public record FileMetadataDto(String id, String uploaderId, String purpose, String filename, String mimeType,
                               long sizeBytes, String status, String rejectionReason, String downloadUrl,
                               Instant createdAt, Instant updatedAt) {
}
