package cn.timebank.common.scheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * D5: this codebase's first scheduled job (B5.8's summary materialization,
 * later joined by B5.11's lease-reclaim sweep) needs a distributed lock so a
 * two-instance deployment (Design.md §17.1) never runs the same job twice
 * concurrently. JDBC-backed via the {@code shedlock} table (V9), not Redis —
 * consistent with D1's "no Redis" decision; the lock is exactly as durable
 * and available as the database already is.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
