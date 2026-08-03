package cn.timebank.organization.application.dto;

public record SystemSettingsDto(int notificationPollIntervalSeconds, Long version) {
}
