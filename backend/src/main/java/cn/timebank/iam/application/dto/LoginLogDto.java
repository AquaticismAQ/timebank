package cn.timebank.iam.application.dto;

import java.time.Instant;

public record LoginLogDto(String id, String userId, String usernameAttempted, boolean success,
                           String failureReason, String ipAddress, Instant createdAt) {
}
