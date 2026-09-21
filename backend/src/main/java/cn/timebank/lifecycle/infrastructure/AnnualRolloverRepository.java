package cn.timebank.lifecycle.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * The annual rollover's writes (Design/*&#47;14-AnnualRollover.md), as raw SQL in
 * the same cross-module read-model style {@link PurgeQueryRepository}
 * established: this process necessarily spans `student`, `class`, `account`,
 * `user` and `academic_year`, and expressing it as five modules' services
 * calling each other would make the single-transaction guarantee harder to see
 * rather than easier.
 *
 * <p>Every method here is idempotent on its own, which is what lets the caller
 * retry a failed year without a compensating step.</p>
 */
@Repository
public class AnnualRolloverRepository {

    /** Stage entry grades — a student is always 6 + n or 9 + n years in (§5.1). */
    public static final int JUNIOR_ENTRY_LEVEL = 6;
    public static final int SENIOR_ENTRY_LEVEL = 9;
    /** The last grade of each stage: finishing it graduates the student (§2). */
    public static final List<Integer> EXIT_LEVELS = List.of(8, 12);
    /** Promotion runs highest-first so a student cannot be advanced twice in one pass. */
    public static final List<Integer> PROMOTION_LEVELS_DESC = List.of(11, 10, 9, 7, 6);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AnnualRolloverRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── The target year ──────────────────────────────────────────────────

    /**
     * Creates the target academic year if it does not exist. A deployment seeded
     * two years ago has no row for the year now starting, and the rollover
     * cannot promote into a year that is not there.
     *
     * <p>The dates are passed in rather than computed here: the boundary is
     * configurable ({@code AcademicYearCalendar}), and a second copy of the
     * arithmetic in this class is exactly how the two would come to disagree.</p>
     *
     * <p>Its own transaction, so the year is committed before the run row that
     * carries a foreign key to it is written on another connection. Committing
     * early is safe because the insert is idempotent and a year that exists
     * without a rollover having happened is harmless — it is not marked current
     * until the run succeeds.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureAcademicYear(String yearId, int startYear, LocalDate start, LocalDate end) {
        jdbcTemplate.update("""
                insert ignore into academic_year (id, code, start_date, end_date, is_current, created_at, updated_at)
                values (:id, :code, :start, :end, 0, now(3), now(3))
                """, new MapSqlParameterSource()
                .addValue("id", yearId)
                .addValue("code", startYear + "-" + (startYear + 1))
                .addValue("start", start)
                .addValue("end", end));
    }

    /**
     * Row-locks the target year for the duration of the transaction. Two
     * instances reaching 1 September together then serialise here rather than
     * both promoting.
     */
    public void lockAcademicYear(String yearId) {
        jdbcTemplate.queryForList("select id from academic_year where id = :id for update",
                new MapSqlParameterSource("id", yearId), String.class);
    }

    public boolean hasSucceededFor(String yearId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from annual_rollover_run where academic_year_id = :id and status = 'SUCCEEDED'",
                new MapSqlParameterSource("id", yearId), Integer.class);
        return n != null && n > 0;
    }

    public int markCurrent(String yearId) {
        return jdbcTemplate.update("""
                update academic_year set is_current = (id = :id), updated_at = now(3)
                where is_current <> (id = :id)
                """, new MapSqlParameterSource("id", yearId));
    }

    // ── Classes ──────────────────────────────────────────────────────────

    public record ClassRow(String id, String code, String name, String gradeId) {
    }

    public List<ClassRow> classesIn(String yearId) {
        return jdbcTemplate.query("select id, code, name, grade_id from class where academic_year_id = :y",
                new MapSqlParameterSource("y", yearId),
                (rs, i) -> new ClassRow(rs.getString("id"), rs.getString("code"),
                        rs.getString("name"), rs.getString("grade_id")));
    }

    public int createClass(String id, String code, String name, String gradeId, String yearId) {
        return jdbcTemplate.update("""
                insert ignore into class (id, code, name, grade_id, academic_year_id, created_at, updated_at)
                values (:id, :code, :name, :grade, :year, now(3), now(3))
                """, new MapSqlParameterSource().addValue("id", id).addValue("code", code)
                .addValue("name", name).addValue("grade", gradeId).addValue("year", yearId));
    }

    // ── Students ─────────────────────────────────────────────────────────

    /**
     * Graduates everyone finishing a stage. Runs <em>before</em> promotion, or
     * grade 8 would be advanced into grade 9 — which is precisely the migration
     * §2.1 refuses to perform.
     */
    public int graduateExitGrades() {
        return jdbcTemplate.update("""
                update student set status = 'GRADUATED', updated_at = now(3)
                where status = 'ACTIVE' and grade_id in (:grades)
                """, new MapSqlParameterSource("grades",
                EXIT_LEVELS.stream().map(l -> "grade_" + l).toList()));
    }

    /**
     * Promotes one grade, moving each student into the same-suffixed class of
     * the target year. The join is on {@code class.name} — the bare suffix
     * ("A", "Mars") — because the code carries the grade prefix and therefore
     * differs on purpose between the two rows.
     *
     * <p>A student whose suffix has no counterpart in the target year is left
     * untouched and counted as skipped by {@link #countStudentsAt}, rather than
     * being promoted into a null class.</p>
     */
    public int promoteGrade(int fromLevel, String targetYearId) {
        return jdbcTemplate.update("""
                update student s
                  join class oldc on oldc.id = s.class_id
                  join class newc on newc.academic_year_id = :year
                                 and newc.grade_id = :toGrade
                                 and newc.name = oldc.name
                   set s.grade_id = :toGrade, s.class_id = newc.id, s.updated_at = now(3)
                 where s.status = 'ACTIVE' and s.grade_id = :fromGrade
                """, new MapSqlParameterSource()
                .addValue("year", targetYearId)
                .addValue("fromGrade", "grade_" + fromLevel)
                .addValue("toGrade", "grade_" + (fromLevel + 1)));
    }

    public int countStudentsAt(int level) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from student where status = 'ACTIVE' and grade_id = :g",
                new MapSqlParameterSource("g", "grade_" + level), Integer.class);
        return n == null ? 0 : n;
    }

    // ── The live grade snapshot (§3.4) ───────────────────────────────────

    /**
     * Re-points every account at its student's new grade and class.
     *
     * <p>An account is current state, not a historical event: scope filtering
     * reads {@code account.grade_id} directly, so leaving it behind makes a
     * teacher lose their own students' accounts the moment promotion runs —
     * the data present, simply outside scope. Service records, cases and
     * proposals are deliberately <em>not</em> touched: their grade is the fact
     * they record.</p>
     */
    public int resyncAccountSnapshots() {
        return jdbcTemplate.update("""
                update account a join student s on s.id = a.student_id
                   set a.grade_id = s.grade_id, a.class_id = s.class_id, a.updated_at = now(3)
                 where a.grade_id <> s.grade_id or a.class_id <> s.class_id
                """, new MapSqlParameterSource());
    }

    /** Graduating leaves one usable identity per person: the old login is deactivated (§3.1). */
    public int deactivateGraduatedLogins() {
        return jdbcTemplate.update("""
                update user u join student s on s.id = u.student_id
                   set u.status = 'INACTIVE', u.updated_at = now(3)
                 where s.status = 'GRADUATED' and u.status = 'ACTIVE'
                """, new MapSqlParameterSource());
    }

    // ── §5's consistency check ───────────────────────────────────────────

    /**
     * Counts active students whose stored grade no longer matches their
     * enrolment cohort. Every student should be exactly 6 + n or 9 + n years
     * past their entry year; anything else means a year's rollover was missed,
     * or that student repeated or skipped one.
     *
     * <p>This is the only signal that will ever reveal a missed year after the
     * fact, so it is recorded on the run rather than merely logged.</p>
     */
    public int countInconsistentGrades(int targetYearStart) {
        Integer n = jdbcTemplate.queryForObject("""
                select count(*)
                  from student s
                  join academic_year ay on ay.id = s.enrollment_academic_year_id
                 where s.status = 'ACTIVE'
                   and (cast(substring(s.grade_id, 7) as signed) - (:target - year(ay.start_date)))
                       not in (:entryLevels)
                """, new MapSqlParameterSource()
                .addValue("target", targetYearStart)
                .addValue("entryLevels", List.of(JUNIOR_ENTRY_LEVEL, SENIOR_ENTRY_LEVEL)), Integer.class);
        return n == null ? 0 : n;
    }
}
