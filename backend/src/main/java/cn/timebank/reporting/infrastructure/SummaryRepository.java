package cn.timebank.reporting.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** B5.8: read/write for the materialized `grade_participation_summary`/`grade_compliance_summary` tables — never touched by JPA (D6: reporting stays JdbcTemplate end to end). */
@Repository
public class SummaryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SummaryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ParticipationSummaryRow(String gradeId, int totalStudents, int participatedStudents, Instant generatedAt) {
    }

    public void upsertParticipation(String gradeId, String academicYearId, int total, int participated, Instant generatedAt) {
        String sql = """
                insert into grade_participation_summary (grade_id, academic_year_id, total_students, participated_students, generated_at)
                values (:gradeId, :yearId, :total, :participated, :generatedAt)
                on duplicate key update total_students = :total, participated_students = :participated, generated_at = :generatedAt
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("gradeId", gradeId).addValue("yearId", academicYearId)
                .addValue("total", total).addValue("participated", participated).addValue("generatedAt", generatedAt));
    }

    public List<ParticipationSummaryRow> participationForYear(String academicYearId) {
        String sql = """
                select grade_id, total_students, participated_students, generated_at
                from grade_participation_summary where academic_year_id = :yearId
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("yearId", academicYearId), (rs, rowNum) ->
                new ParticipationSummaryRow(rs.getString("grade_id"), rs.getInt("total_students"),
                        rs.getInt("participated_students"), rs.getTimestamp("generated_at").toInstant()));
    }

    public record ComplianceSummaryRow(String gradeId, int totalStudents, int completedStudents, int requiredMinutes, Instant generatedAt) {
    }

    public void upsertCompliance(String gradeId, String academicYearId, int total, int completed, int requiredMinutes, Instant generatedAt) {
        String sql = """
                insert into grade_compliance_summary (grade_id, academic_year_id, total_students, completed_students, required_minutes, generated_at)
                values (:gradeId, :yearId, :total, :completed, :required, :generatedAt)
                on duplicate key update total_students = :total, completed_students = :completed,
                    required_minutes = :required, generated_at = :generatedAt
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("gradeId", gradeId).addValue("yearId", academicYearId).addValue("total", total)
                .addValue("completed", completed).addValue("required", requiredMinutes).addValue("generatedAt", generatedAt));
    }

    public List<ComplianceSummaryRow> complianceForYear(String academicYearId) {
        String sql = """
                select grade_id, total_students, completed_students, required_minutes, generated_at
                from grade_compliance_summary where academic_year_id = :yearId
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("yearId", academicYearId), (rs, rowNum) ->
                new ComplianceSummaryRow(rs.getString("grade_id"), rs.getInt("total_students"),
                        rs.getInt("completed_students"), rs.getInt("required_minutes"), rs.getTimestamp("generated_at").toInstant()));
    }
}
