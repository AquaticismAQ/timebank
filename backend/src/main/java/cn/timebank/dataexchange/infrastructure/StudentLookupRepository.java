package cn.timebank.dataexchange.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** B5.10: import row validation needs "does this student number exist and is it active" — a read-only lookup, not a write, so raw SQL against `student` stays the legitimate read-model exception (same as `ReviewQueueRepository`). */
@Repository
public class StudentLookupRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StudentLookupRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record StudentLookupRow(String id, String gradeId, String classId, String status) {
    }

    public Optional<StudentLookupRow> findByStudentNumber(String studentNumber) {
        String sql = "select id, grade_id, class_id, status from student where student_number = :num";
        List<StudentLookupRow> rows = jdbcTemplate.query(sql, new MapSqlParameterSource("num", studentNumber),
                (rs, rowNum) -> new StudentLookupRow(rs.getString("id"), rs.getString("grade_id"),
                        rs.getString("class_id"), rs.getString("status")));
        return rows.stream().findFirst();
    }
}
