package cn.timebank.file.application.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Contract §4.6: "the server returns the allowed upload URL, request
 * headers, expiration, and fileId." {@code uploadUrl} points at this app's
 * own {@code PUT /files/{id}/content} in this test-stage, local-disk pass
 * — a real S3-compatible adapter would return a genuinely external,
 * pre-signed URL instead, with no change to this DTO's shape.
 */
public record UploadIntentDto(String fileId, String uploadUrl, Map<String, String> uploadHeaders, Instant expiresAt) {
}
