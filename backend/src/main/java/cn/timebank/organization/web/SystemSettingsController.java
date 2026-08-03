package cn.timebank.organization.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.organization.application.SystemSettingsService;
import cn.timebank.organization.application.dto.SystemSettingsDto;
import cn.timebank.organization.application.dto.SystemSettingsRequests.UpdateSystemSettingsRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Contract §4.15. */
@RestController
public class SystemSettingsController {

    private final SystemSettingsService systemSettingsService;

    public SystemSettingsController(SystemSettingsService systemSettingsService) {
        this.systemSettingsService = systemSettingsService;
    }

    @GetMapping("/api/v1/system/settings")
    @PreAuthorize("hasAuthority('settings.read')")
    public ApiResponse<SystemSettingsDto> getSettings() {
        return ApiResponse.of(systemSettingsService.getSettings());
    }

    @PatchMapping("/api/v1/system/settings")
    @PreAuthorize("hasAuthority('settings.manage')")
    public ApiResponse<SystemSettingsDto> updateSettings(
            @Valid @RequestBody UpdateSystemSettingsRequest request, Authentication auth) {
        String actorId = ((CookieSessionUserDetails) auth.getPrincipal()).id();
        return ApiResponse.of(systemSettingsService.updateSettings(request, actorId));
    }
}
