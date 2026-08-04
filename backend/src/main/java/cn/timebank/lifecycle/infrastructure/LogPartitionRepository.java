package cn.timebank.lifecycle.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * B6.1: `audit_log`/`login_log` are `PARTITION BY RANGE (TO_DAYS(created_at))`
 * with monthly partitions pre-created through a fixed date (V1/V3) plus a
 * `p_future` catch-all. This keeps that window rolling forward and prunes
 * whatever has aged out of the 1-year retention (Design.md §16.3) — always
 * `ALTER TABLE ... {REORGANIZE|DROP} PARTITION`, never a `DELETE`.
 */
@Repository
public class LogPartitionRepository {

    private static final DateTimeFormatter PARTITION_SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public LogPartitionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Named partitions only (excludes MySQL's synthetic whole-table row for a partitioned table when queried without a name), ordered oldest first. */
    public List<String> namedPartitions(String tableName) {
        String sql = """
                select partition_name from information_schema.partitions
                where table_schema = database() and table_name = :table and partition_name is not null
                order by partition_ordinal_position
                """;
        return jdbcTemplate.queryForList(sql, new MapSqlParameterSource("table", tableName), String.class);
    }

    /** Adds a named monthly partition for {@code month}, carved out of `p_future` (which is re-added after it). No-op if the partition already exists. */
    public void ensurePartitionExists(String tableName, YearMonth month, List<String> existingPartitions) {
        String name = "p_" + month.format(PARTITION_SUFFIX);
        if (existingPartitions.contains(name)) {
            return;
        }
        LocalDate boundary = month.plusMonths(1).atDay(1);
        String sql = String.format("""
                alter table %s reorganize partition p_future into (
                    partition %s values less than (to_days('%s')),
                    partition p_future values less than maxvalue
                )
                """, tableName, name, boundary);
        jdbcTemplate.getJdbcTemplate().execute(sql);
    }

    /** Drops a partition outright — instant regardless of row count, the entire point of partitioning by date (Design.md §16.3). */
    public void dropPartition(String tableName, String partitionName) {
        jdbcTemplate.getJdbcTemplate().execute(String.format("alter table %s drop partition %s", tableName, partitionName));
    }
}
