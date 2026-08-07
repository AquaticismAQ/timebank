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

    /** B5.10b (student roster import): does a row with this student number already exist, anywhere — not just active. */
    public boolean studentNumberExists(String studentNumber) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from student where student_number = :num",
                new MapSqlParameterSource("num", studentNumber), Integer.class);
        return count != null && count > 0;
    }

    public boolean gradeExists(String gradeId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from grade where id = :id",
                new MapSqlParameterSource("id", gradeId), Integer.class);
        return count != null && count > 0;
    }

    /** Empty when the class doesn't exist at all; otherwise its actual `grade_id`, for the caller to check the roster row's claimed grade actually matches. */
    public Optional<String> classGradeId(String classId) {
        String sql = "select grade_id from class where id = :id";
        List<String> rows = jdbcTemplate.query(sql, new MapSqlParameterSource("id", classId),
                (rs, rowNum) -> rs.getString("grade_id"));
        return rows.stream().findFirst();
    }

    public boolean academicYearExists(String academicYearId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from academic_year where id = :id",
                new MapSqlParameterSource("id", academicYearId), Integer.class);
        return count != null && count > 0;
    }
}
