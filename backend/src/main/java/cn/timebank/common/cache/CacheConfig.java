package cn.timebank.common.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine, not Redis (D1) — dictionaries, categories, grades, school info
 * are kilobytes, low-churn, and the bound is a TTL, not an eviction policy
 * problem. One named region ({@link #DICTIONARIES}) for everything B2.12
 * caches; call sites also {@code @CacheEvict} it directly on their own
 * mutations, and rule publication evicts it too since a newly-effective
 * rule version can change what "current" dictionary data means without
 * mutating the dictionary rows themselves.
 *
 * 60 s TTL is an acceptable staleness window per-instance (D1's
 * "per-instance cache coherence" note) precisely because nothing cached
 * here is read for a security decision.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String DICTIONARIES = "dictionaries";

    /**
     * B5.6: dashboards cache 30-60 s and are viewer-derived (Design.md
     * §16.2) — every call site keys explicitly on the caller's own
     * principal (never a no-arg key, the B2.12 collision lesson), so this
     * single region is safe to share across the three dashboard endpoints.
     */
    public static final String DASHBOARDS = "dashboards";

    @Bean
    public CaffeineCacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(DICTIONARIES, DASHBOARDS);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(10_000));
        return manager;
    }
}
