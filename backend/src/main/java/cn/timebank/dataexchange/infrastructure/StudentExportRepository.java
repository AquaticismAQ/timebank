package cn.timebank.dataexchange.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Student roster export — keyset-paginated on {@code id}, same pattern as
 * {@link ServiceRecordExportRepository}. Exports the fields visible in
 * Student Management (display name, student number, grade, class, status,
 * graduation year, joined date).
 */
@Repository
public class StudentExportRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StudentExportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ExportRow(String id, String studentNumber, String displayName,
                             String gradeId, String classId, String status,
                             int graduationYear, LocalDate joinedAt) {
    }

    public List<ExportRow> page(String afterId, int limit) {
        String sql = """
                select id, student_number, display_name, grade_id, class_id, status, graduation_year, joined_at
                from student
                where id > :afterId
                order by id asc
                limit :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("afterId", afterId == null ? "" : afterId).addValue("limit", limit);
        return jdbcTemplate.query(sql, params, (rs, rowNum) -> new ExportRow(
                rs.getString("id"), rs.getString("student_number"), rs.getString("display_name"),
                rs.getString("grade_id"), rs.getString("class_id"), rs.getString("status"),
                rs.getInt("graduation_year"), rs.getObject("joined_at", LocalDate.class)));
    }
}
