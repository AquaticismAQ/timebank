package cn.timebank.organization.application;

import cn.timebank.organization.application.dto.SystemSettingsDto;
import cn.timebank.organization.application.dto.SystemSettingsRequests.UpdateSystemSettingsRequest;

public interface SystemSettingsService {

    SystemSettingsDto getSettings();

    SystemSettingsDto updateSettings(UpdateSystemSettingsRequest request, String actorId);
}
