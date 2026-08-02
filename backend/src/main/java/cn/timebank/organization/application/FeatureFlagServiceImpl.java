package cn.timebank.organization.application;

import cn.timebank.common.cache.CacheConfig;
import cn.timebank.organization.application.dto.FeatureFlagsDto;
import cn.timebank.organization.domain.FeatureFlag;
import cn.timebank.organization.infrastructure.FeatureFlagRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
class FeatureFlagServiceImpl implements FeatureFlagService {

    private final FeatureFlagRepository repository;

    FeatureFlagServiceImpl(FeatureFlagRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    // Explicit key — see SchoolInfoServiceImpl.getInfo() for why every no-arg
    // @Cacheable method in this shared cache region needs a distinct one.
    @Cacheable(value = CacheConfig.DICTIONARIES, key = "'featureFlags'")
    public FeatureFlagsDto getFlags() {
        Map<String, Boolean> byCode = repository.findAll().stream()
                .collect(Collectors.toMap(FeatureFlag::getCode, FeatureFlag::isEnabled));
        return new FeatureFlagsDto(
                byCode.getOrDefault("pov", false),
                byCode.getOrDefault("redemption", false),
                byCode.getOrDefault("borrowing", false),
                byCode.getOrDefault("appreciation", false));
    }
}
