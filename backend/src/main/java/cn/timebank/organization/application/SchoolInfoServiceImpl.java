package cn.timebank.organization.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.cache.CacheConfig;
import cn.timebank.common.error.ApiException;
import cn.timebank.organization.application.dto.SchoolInfoDto;
import cn.timebank.organization.application.dto.SchoolInfoRequests.UpdateSchoolInfoRequest;
import cn.timebank.organization.domain.SchoolInfo;
import cn.timebank.organization.infrastructure.SchoolInfoRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class SchoolInfoServiceImpl implements SchoolInfoService {

    private final SchoolInfoRepository repository;
    private final AuditService auditService;

    SchoolInfoServiceImpl(SchoolInfoRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Override
    @Transactional(readOnly = true)
    // Explicit key: every other no-arg @Cacheable method in this cache region
    // needs one too, or they all collide on Spring's default SimpleKey.EMPTY
    // and one clobbers another with a wrongly-typed cached value (caught live
    // via a ClassCastException between this and FeatureFlagsDto).
    @Cacheable(value = CacheConfig.DICTIONARIES, key = "'schoolInfo'")
    public SchoolInfoDto getInfo() {
        return toDto(theRow());
    }

    @Override
    @Transactional(readOnly = true)
    public String getDefaultLanguage() {
        return theRow().getDefaultLanguage();
    }

    @Override
    @Transactional
    @CacheEvict(value = CacheConfig.DICTIONARIES, key = "'schoolInfo'")
    public SchoolInfoDto update(UpdateSchoolInfoRequest request, boolean isSysadmin, String actorId) {
        if (!isSysadmin) {
            throw ApiException.forbidden("Only a system administrator may edit school info.");
        }
        SchoolInfo info = theRow();
        SchoolInfoDto before = toDto(info);
        if (request.name() != null) {
            info.setName(request.name());
        }
        if (request.fullName() != null) {
            info.setFullName(request.fullName());
        }
        if (request.studentDevCenter() != null) {
            info.setStudentDevCenter(request.studentDevCenter());
        }
        if (request.contact() != null) {
            info.setContact(request.contact());
        }
        info.setUpdatedAt(Instant.now());
        repository.saveAndFlush(info);

        SchoolInfoDto after = toDto(info);
        auditService.record(new AuditEntry(actorId, "settings.manage", "schoolInfo.update",
                "schoolInfo", info.getId(), before, after, null));
        return after;
    }

    private SchoolInfo theRow() {
        // D8: exactly one row exists (seeded by R__seed_base_data.sql).
        return repository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("school_info has no row — seed migration did not run"));
    }

    private SchoolInfoDto toDto(SchoolInfo info) {
        return new SchoolInfoDto(info.getName(), info.getFullName(), info.getStudentDevCenter(), info.getContact(),
                info.getCurrentAcademicYearCode());
    }
}
