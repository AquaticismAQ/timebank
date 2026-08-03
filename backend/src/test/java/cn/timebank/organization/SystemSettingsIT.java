package cn.timebank.organization;

import cn.timebank.common.error.ApiException;
import cn.timebank.organization.application.SystemSettingsService;
import cn.timebank.organization.application.dto.SystemSettingsDto;
import cn.timebank.organization.application.dto.SystemSettingsRequests.UpdateSystemSettingsRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B5.5/B5.12's exit criterion: the seeded singleton row round-trips and enforces optimistic concurrency. */
class SystemSettingsIT extends AbstractIntegrationTest {

    @Autowired
    private SystemSettingsService systemSettingsService;

    @Test
    void theSeededDefaultRowIsReadableOnStartup() {
        SystemSettingsDto settings = systemSettingsService.getSettings();
        assertThat(settings.notificationPollIntervalSeconds()).isEqualTo(30);
        assertThat(settings.version()).isNotNull();
    }

    @Test
    void updateRoundTripsAndBumpsVersion() {
        SystemSettingsDto before = systemSettingsService.getSettings();

        SystemSettingsDto updated = systemSettingsService.updateSettings(
                new UpdateSystemSettingsRequest(45, before.version()), "usr_test_admin");

        assertThat(updated.notificationPollIntervalSeconds()).isEqualTo(45);
        assertThat(updated.version()).isGreaterThan(before.version());

        // Restore, so this test doesn't leave the seeded default permanently altered for later tests in the same run.
        systemSettingsService.updateSettings(new UpdateSystemSettingsRequest(30, updated.version()), "usr_test_admin");
    }

    @Test
    void aStaleVersionIsRejected() {
        SystemSettingsDto current = systemSettingsService.getSettings();
        assertThatThrownBy(() -> systemSettingsService.updateSettings(
                new UpdateSystemSettingsRequest(60, current.version() - 1), "usr_test_admin"))
                .isInstanceOf(ApiException.class);
    }
}
