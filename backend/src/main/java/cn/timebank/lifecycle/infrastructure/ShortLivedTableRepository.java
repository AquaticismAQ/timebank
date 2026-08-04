package cn.timebank.lifecycle.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/** B6.2: `idempotency_record` "expires in days" (Design.md §16.3) — a plain age-based `DELETE`, unlike the log tables, since it's small and never partitioned (B0.7). */
@Repository
public class ShortLivedTableRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ShortLivedTableRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int deleteIdempotencyRecordsOlderThan(Instant cutoff) {
        return jdbcTemplate.update("delete from idempotency_record where updated_at < :cutoff",
                new MapSqlParameterSource("cutoff", cutoff));
    }
}
