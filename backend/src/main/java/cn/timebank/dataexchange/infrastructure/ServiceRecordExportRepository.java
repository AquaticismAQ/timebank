package cn.timebank.dataexchange.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * B5.9's "serviceRecords" export — the cohort-preservation dataset
 * (Design.md §16.3). Keyset (seek) pagination on {@code id}, not {@code
 * OFFSET}, per Design.md §16.1: an export walking the full table must not
 * degrade into an O(n²) scan as it goes deeper.
 */
@Repository
public class ServiceRecordExportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ServiceRecordExportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ExportRow(String id, String studentId, String studentName, LocalDate serviceDate,
                             int durationMinutes, Integer creditedUnits, String status) {
    }

    public List<ExportRow> page(String afterId, int limit) {
        String sql = """
                select id, student_id, student_name, service_date, duration_minutes, credited_units, status
                from service_record
                where id > :afterId
                order by id asc
                limit :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("afterId", afterId == null ? "" : afterId).addValue("limit", limit);
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> new ExportRow(
                rs.getString("id"), rs.getString("student_id"), rs.getString("student_name"),
                rs.getObject("service_date", LocalDate.class), rs.getInt("duration_minutes"),
                (Integer) rs.getObject("credited_units"), rs.getString("status")));
    }

    public long countAll() {
        Long result = jdbcTemplate.getJdbcTemplate().queryForObject("select count(*) from service_record", Long.class);
        return result == null ? 0 : result;
    }
}
