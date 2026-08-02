package cn.timebank.organization.application;

import cn.timebank.common.cache.CacheConfig;
import cn.timebank.organization.application.dto.SchoolInfoDto;
import cn.timebank.organization.domain.SchoolInfo;
import cn.timebank.organization.infrastructure.SchoolInfoRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SchoolInfoServiceImpl implements SchoolInfoService {

    private final SchoolInfoRepository repository;

    SchoolInfoServiceImpl(SchoolInfoRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    // Explicit key: every other no-arg @Cacheable method in this cache region
    // needs one too, or they all collide on Spring's default SimpleKey.EMPTY
    // and one clobbers another with a wrongly-typed cached value (caught live
    // via a ClassCastException between this and FeatureFlagsDto).
    @Cacheable(value = CacheConfig.DICTIONARIES, key = "'schoolInfo'")
    public SchoolInfoDto getInfo() {
        SchoolInfo info = theRow();
        return new SchoolInfoDto(info.getName(), info.getStudentDevCenter(), info.getContact(),
                info.getCurrentAcademicYearCode());
    }

    @Override
    @Transactional(readOnly = true)
    public String getDefaultLanguage() {
        return theRow().getDefaultLanguage();
    }

    private SchoolInfo theRow() {
        // D8: exactly one row exists (seeded by R__seed_base_data.sql).
        return repository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("school_info has no row — seed migration did not run"));
    }
}
