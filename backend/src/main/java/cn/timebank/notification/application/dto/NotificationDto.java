package cn.timebank.notification.application.dto;

import java.time.Instant;

public record NotificationDto(String id, String templateCode, String title, String body,
                               String relatedResourceType, String relatedResourceId, boolean read, Instant createdAt) {
}
