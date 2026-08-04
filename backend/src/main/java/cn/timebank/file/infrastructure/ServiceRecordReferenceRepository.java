package cn.timebank.file.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * A legitimate cross-module *read-model query* (Design.md §6's "or a
 * read-model query" clause, the same escape valve `review`'s queue
 * projection uses) — raw SQL against `service_record` carries no Java
 * class dependency on `servicerecord.domain`, so it doesn't cross
 * ArchUnit's module-boundary rule. Used to answer two questions `file`
 * needs without owning: "which records reference this evidence file?" (for
 * B3.5's scope check, delegated to `ServiceRecordService.getDetail` per
 * record id rather than re-implementing scope logic here) and "is any of
 * them not a draft?" (B3.4's delete guard).
 */
@Repository
public class ServiceRecordReferenceRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ServiceRecordReferenceRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * {@code studentId}/{@code gradeId}/{@code classId} are carried here so
     * {@code FileServiceImpl} can apply B1.7's scope check itself — deliberately
     * *not* by calling {@code ServiceRecordService}, which would create a
     * `file` ↔ `servicerecord` module cycle (`servicerecord` already calls
     * `file` for B3.3's evidence-readiness check).
     */
    public record Reference(String recordId, String status, String studentId, String gradeId, String classId) {
    }

    public List<Reference> findRecordsReferencing(String fileId) {
        String sql = """
                select id, status, student_id, grade_id, class_id from service_record
                where JSON_CONTAINS(evidence_file_ids, JSON_QUOTE(:fileId))
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource("fileId", fileId),
                (rs, rowNum) -> new Reference(rs.getString("id"), rs.getString("status"),
                        rs.getString("student_id"), rs.getString("grade_id"), rs.getString("class_id")));
    }

    /** B6.5: every evidence file id attached to any of a student's service records, across every record regardless of status — the purge needs all of them, not just draft-eligible ones. */
    public List<String> findFileIdsForStudent(String studentId) {
        String sql = """
                select evidence_file_ids from service_record where student_id = :studentId and evidence_file_ids is not null
                """;
        List<String> jsonArrays = jdbcTemplate.queryForList(sql, new MapSqlParameterSource("studentId", studentId), String.class);
        return jsonArrays.stream()
                .flatMap(json -> java.util.Arrays.stream(json.replaceAll("[\\[\\]\"]", "").split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }
}
