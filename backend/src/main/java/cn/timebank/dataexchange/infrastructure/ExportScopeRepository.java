package cn.timebank.dataexchange.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The two small reads {@code ExportScopeResolver} needs: which academic year is
 * which, and which students fall inside a scope.
 *
 * <p>Separate from the per-type export repositories because it answers a
 * question about <em>who</em> rather than about any one report's rows, and every
 * type asks it.</p>
 */
@Repository
public class ExportScopeRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ExportScopeRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * The calendar year the current academic year begins in — 2026 for 2026-2027.
     *
     * <p>Read from {@code start_date}, never parsed out of {@code code}: the code
     * is a label and the date is the fact. Falls back to the calendar year only
     * if no year is marked current, which would be a seeding fault rather than a
     * normal state, and is better than failing every export.</p>
     */
    public int currentAcademicYearStartYear() {
        List<Integer> years = jdbcTemplate.queryForList(
                "select year(start_date) from academic_year where is_current = 1 order by start_date desc limit 1",
                new MapSqlParameterSource(), Integer.class);
        return years.isEmpty() ? java.time.LocalDate.now().getYear() : years.get(0);
    }

    public Optional<Integer> academicYearStartYear(String academicYearId) {
        List<Integer> years = jdbcTemplate.queryForList(
                "select year(start_date) from academic_year where id = :id",
                new MapSqlParameterSource("id", academicYearId), Integer.class);
        return years.isEmpty() ? Optional.empty() : Optional.of(years.get(0));
    }

    /**
     * Students inside a scope, as the <b>intersection</b> of every restriction
     * given — a teacher's binding and the filters they chose.
     *
     * <p>Intersection, never union (§4.3): a filter may only narrow. A teacher
     * bound to grade 6 who filters to grade 6 gets grade 6; there is no
     * combination of inputs here that widens what they can see.</p>
     *
     * <p>Matched on the student's <b>live</b> grade and class (§7.2), which is
     * the point — scope is about who a teacher is responsible for now, not about
     * what a record says happened.</p>
     */
    public Set<String> studentIdsWithin(Set<String> boundGradeIds, Set<String> boundClassIds,
                                        String gradeFilter, String classFilter) {
        StringBuilder sql = new StringBuilder("select id from student where 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (boundGradeIds != null) {
            if (boundGradeIds.isEmpty()) {
                return Set.of(); // assigned to no grade: matches nobody, and says so
            }
            sql.append(" and grade_id in (:boundGradeIds)");
            params.addValue("boundGradeIds", boundGradeIds);
        }
        if (boundClassIds != null) {
            if (boundClassIds.isEmpty()) {
                return Set.of();
            }
            sql.append(" and class_id in (:boundClassIds)");
            params.addValue("boundClassIds", boundClassIds);
        }
        if (gradeFilter != null) {
            sql.append(" and grade_id = :gradeFilter");
            params.addValue("gradeFilter", gradeFilter);
        }
        if (classFilter != null) {
            sql.append(" and class_id = :classFilter");
            params.addValue("classFilter", classFilter);
        }

        return new LinkedHashSet<>(jdbcTemplate.queryForList(sql.toString(), params, String.class));
    }

    /** Does this id exist? Used to 404 a filter rather than return a file that silently ignored it. */
    public boolean gradeExists(String gradeId) {
        return exists("select count(*) from grade where id = :id", gradeId);
    }

    public boolean classExists(String classId) {
        return exists("select count(*) from class where id = :id", classId);
    }

    public boolean userExists(String userId) {
        return exists("select count(*) from user where id = :id", userId);
    }

    private boolean exists(String sql, String id) {
        Integer n = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource("id", id), Integer.class);
        return n != null && n > 0;
    }

    /** Grade id → its numeric level, for the derivation. {@code grade_6} is 6; nothing else parses the id. */
    public java.util.Map<String, Integer> gradeLevelsById() {
        java.util.Map<String, Integer> levels = new java.util.HashMap<>();
        jdbcTemplate.query("select id, code from grade", new MapSqlParameterSource(), rs -> {
            String id = rs.getString("id");
            Integer level = numericSuffix(id);
            if (level == null) {
                level = numericSuffix(rs.getString("code"));
            }
            if (level != null) {
                levels.put(id, level);
            }
        });
        return levels;
    }

    /** Grade id → the stable display name used as a column value. */
    public java.util.Map<String, String> gradeNamesById(boolean chinese) {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        jdbcTemplate.query("select id, name_zh, name_en from grade", new MapSqlParameterSource(),
                rs -> {
                    names.put(rs.getString("id"), rs.getString(chinese ? "name_zh" : "name_en"));
                });
        return names;
    }

    /**
     * Class id → {@code class.code}, not {@code class.name}.
     *
     * <p>`Docs/issues/107` §3: {@code name} is the bare suffix ("A", "Mars"),
     * which is ambiguous on its own — 9A and 10A share it. The code carries the
     * grade prefix and is what a reader can act on.</p>
     */
    public java.util.Map<String, String> classCodesById() {
        java.util.Map<String, String> codes = new java.util.HashMap<>();
        jdbcTemplate.query("select id, code from class", new MapSqlParameterSource(),
                rs -> {
                    codes.put(rs.getString("id"), rs.getString("code"));
                });
        return codes;
    }

    /** Academic year id → the calendar year it starts in, for rendering an enrolment year as a code. */
    public java.util.Map<String, Integer> academicYearStartYearsById() {
        java.util.Map<String, Integer> years = new java.util.HashMap<>();
        jdbcTemplate.query("select id, year(start_date) as y from academic_year", new MapSqlParameterSource(),
                rs -> {
                    years.put(rs.getString("id"), rs.getInt("y"));
                });
        return years;
    }

    private static Integer numericSuffix(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(value);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }
}
