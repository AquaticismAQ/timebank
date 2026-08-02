package cn.timebank.organization;

import cn.timebank.organization.application.FeatureFlagService;
import cn.timebank.organization.application.SchoolInfoService;
import cn.timebank.organization.application.dto.FeatureFlagsDto;
import cn.timebank.organization.application.dto.SchoolInfoDto;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.dto.AcademicYearDto;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2.12's real failure mode: several no-arg {@code @Cacheable(DICTIONARIES)}
 * methods share one cache region. Without an explicit per-method key, Spring's
 * default key generator maps every no-arg call to the same key, so the
 * second cached method silently returns the first one's (wrongly-typed)
 * value — a {@code ClassCastException} in production, first caught here by
 * calling all three back-to-back against real MySQL and Caffeine.
 */
class DictionaryCachingIT extends AbstractIntegrationTest {

    @Autowired
    private SchoolInfoService schoolInfoService;

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private AcademicStructureService academicStructureService;

    @Test
    void noArgCacheableMethodsSharingOneRegionDoNotClobberEachOther() {
        SchoolInfoDto info = schoolInfoService.getInfo();
        FeatureFlagsDto flags = featureFlagService.getFlags();
        List<AcademicYearDto> years = academicStructureService.listAcademicYears();

        assertThat(info.name()).isNotBlank();
        assertThat(flags).isNotNull();
        assertThat(years).isNotEmpty();

        // Re-read each — now served from cache — and confirm the type/shape
        // is still correct rather than another method's cached value.
        assertThat(schoolInfoService.getInfo().name()).isEqualTo(info.name());
        assertThat(featureFlagService.getFlags()).isEqualTo(flags);
        assertThat(academicStructureService.listAcademicYears()).hasSize(years.size());
    }
}
