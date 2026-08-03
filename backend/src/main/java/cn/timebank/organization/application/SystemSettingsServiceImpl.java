package cn.timebank.organization.application;

import cn.timebank.common.concurrency.ConcurrencyControl;
import cn.timebank.organization.application.dto.SystemSettingsDto;
import cn.timebank.organization.application.dto.SystemSettingsRequests.UpdateSystemSettingsRequest;
import cn.timebank.organization.domain.SystemSettings;
import cn.timebank.organization.infrastructure.SystemSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class SystemSettingsServiceImpl implements SystemSettingsService {

    private final SystemSettingsRepository repository;

    SystemSettingsServiceImpl(SystemSettingsRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public SystemSettingsDto getSettings() {
        return toDto(theRow());
    }

    @Override
    @Transactional
    public SystemSettingsDto updateSettings(UpdateSystemSettingsRequest request, String actorId) {
        SystemSettings settings = theRow();
        ConcurrencyControl.checkVersion(settings.getVersion(), request.version());
        if (request.notificationPollIntervalSeconds() != null) {
            settings.setNotificationPollIntervalSeconds(request.notificationPollIntervalSeconds());
        }
        settings.setUpdatedAt(Instant.now());
        settings.setUpdatedBy(actorId);
        repository.saveAndFlush(settings);
        return toDto(settings);
    }

    private SystemSettings theRow() {
        // D8: exactly one row exists (seeded by R__seed_base_data.sql).
        return repository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("system_settings has no row — seed migration did not run"));
    }

    private SystemSettingsDto toDto(SystemSettings s) {
        return new SystemSettingsDto(s.getNotificationPollIntervalSeconds(), s.getVersion());
    }
}
