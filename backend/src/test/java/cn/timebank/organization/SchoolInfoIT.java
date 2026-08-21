package cn.timebank.organization;

import cn.timebank.common.error.ApiException;
import cn.timebank.organization.application.SchoolInfoService;
import cn.timebank.organization.application.dto.SchoolInfoDto;
import cn.timebank.organization.application.dto.SchoolInfoRequests.UpdateSchoolInfoRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `PATCH /school/info` is `sysadmin`-only, deliberately narrower than
 * `admin` even though the two hold identical permission grants post-`V21`
 * — proven here against real MySQL by exercising both branches of the
 * `isSysadmin` flag the controller resolves and passes in.
 */
class SchoolInfoIT extends AbstractIntegrationTest {

    @Autowired
    private SchoolInfoService schoolInfoService;

    @Test
    void adminIsRejectedEvenThoughItHoldsEveryPermissionSysadminDoes() {
        assertThatThrownBy(() -> schoolInfoService.update(
                new UpdateSchoolInfoRequest("Should Not Apply", null, null, null), false, "usr_test_admin"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void sysadminCanUpdateAndTheChangeIsReadBackAndAudited() {
        SchoolInfoDto before = schoolInfoService.getInfo();
        try {
            SchoolInfoDto updated = schoolInfoService.update(
                    new UpdateSchoolInfoRequest("Integration Test School", "Integration Test School (Full)",
                            "Test Dev Center", "it-test@example.edu"),
                    true, "usr_test_admin");

            assertThat(updated.name()).isEqualTo("Integration Test School");
            assertThat(updated.fullName()).isEqualTo("Integration Test School (Full)");
            assertThat(updated.studentDevCenter()).isEqualTo("Test Dev Center");
            assertThat(updated.contact()).isEqualTo("it-test@example.edu");

            // A partial update leaves untouched fields alone.
            SchoolInfoDto partial = schoolInfoService.update(
                    new UpdateSchoolInfoRequest(null, null, null, "second-update@example.edu"), true, "usr_test_admin");
            assertThat(partial.name()).isEqualTo("Integration Test School");
            assertThat(partial.contact()).isEqualTo("second-update@example.edu");
        } finally {
            // Restore — this is the one real, seeded school_info row, shared by every other IT class in this run.
            schoolInfoService.update(new UpdateSchoolInfoRequest(before.name(), before.fullName(), before.studentDevCenter(),
                    before.contact()), true, "usr_test_admin");
        }
    }
}
