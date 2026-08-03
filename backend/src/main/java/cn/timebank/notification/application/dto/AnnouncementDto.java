package cn.timebank.notification.application.dto;

import java.time.Instant;

public record AnnouncementDto(String id, String title, String body, Instant startsAt, Instant endsAt, Instant createdAt) {
}
