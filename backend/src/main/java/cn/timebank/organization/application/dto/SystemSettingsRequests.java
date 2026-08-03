package cn.timebank.organization.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class SystemSettingsRequests {

    private SystemSettingsRequests() {
    }

    /** `PATCH /system/settings` (contract §4.15). */
    public record UpdateSystemSettingsRequest(@Positive Integer notificationPollIntervalSeconds,
                                               @NotNull Long version) {
    }
}
