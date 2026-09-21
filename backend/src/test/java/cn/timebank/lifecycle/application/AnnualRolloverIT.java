package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.application.dto.AnnualRolloverReportDto;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design/*&#47;14-AnnualRollover.md, end to end against real MySQL.
 *
 * <p>Driven through {@code runFor(LocalDate, …)} so a rollover can be performed
 * on a chosen 1 September rather than waiting for one. Everything else — the
 * promotion SQL, the class carry-forward, the account resync, the graduation —
 * is exactly what the scheduled job runs.</p>
 */
class AnnualRolloverIT extends AbstractIntegrationTest {

    @Autowired
    private AnnualRolloverService annualRolloverService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    /**
     * Clears the rollover history between tests.
     *
     * <p>Every test here rolls into the same target year — it has to, because
     * the class carry-forward reads the *previous* year's classes and only the
     * current year has any. The service commits in its own transaction
     * (REQUIRES_NEW), so without this the first test to run would roll the year
     * over and every later one would be correctly told it had already happened.
     * That is the idempotency guard working, not a fixture problem; the guard
     * itself is exercised deliberately in
     * {@link #runningTwiceForOneYearPromotesOnlyOnce}.</p>
     */
    @BeforeEach
    void clearRolloverHistory() {
        // Everything except base data's bootstrap marker for the establishing
        // year — that row is part of the migrated schema, not test residue, and
        // refusesToRollOverTheYearTheSystemWasEstablishedIn depends on it.
        jdbc.update("delete from annual_rollover_run where triggered_by <> 'bootstrap'",
                new MapSqlParameterSource());
    }

    /**
     * The rollover resolves its own target year from the date, so drive it from
     * a chosen September. This test lives in the service's own package for the
     * sole purpose of reaching {@code runFor} — the seam is deliberately not on
     * the public interface, because §8 says this process is not a button and
     * exposing a "roll over year X" method would be the first half of one.
     */
    private AnnualRolloverReportDto rollTo(int yearStart) {
        return impl.runFor(LocalDate.of(yearStart, 9, 1), "test");
    }

    @Autowired
    private AnnualRolloverServiceImpl impl;

    // Injected so the configured-boundary test can build a second service with a
    // different calendar; the real one is wired from application.yml.
    @Autowired
    private cn.timebank.lifecycle.infrastructure.AnnualRolloverRepository repository;
    @Autowired
    private cn.timebank.lifecycle.infrastructure.AnnualRolloverRunRepository runRepository;
    @Autowired
    private cn.timebank.audit.application.AuditService auditService;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private int currentYearStart() {
        LocalDate today = LocalDate.now();
        return today.getMonthValue() >= 9 ? today.getYear() : today.getYear() - 1;
    }

    private String seedStudent(String number, int gradeLevel, String classSuffix, int enrolYearStart) {
        String id = "std_it_" + number;
        String yearId = "ay_" + enrolYearStart + "_" + (enrolYearStart + 1);
        jdbc.update("""
                insert ignore into academic_year (id, code, start_date, end_date, is_current, created_at, updated_at)
                values (:y, :code, :s, :e, 0, now(3), now(3))
                """, new MapSqlParameterSource().addValue("y", yearId)
                .addValue("code", enrolYearStart + "-" + (enrolYearStart + 1))
                .addValue("s", LocalDate.of(enrolYearStart, 9, 1))
                .addValue("e", LocalDate.of(enrolYearStart + 1, 8, 31)));
        jdbc.update("""
                insert into student (id, student_number, display_name, grade_id, class_id,
                                     enrollment_academic_year_id, graduation_year, status, joined_at,
                                     version, created_at, updated_at)
                values (:id, :num, :name, :grade, :class, :year, :grad, 'ACTIVE', :joined, 1, now(3), now(3))
                """, new MapSqlParameterSource().addValue("id", id).addValue("num", number)
                .addValue("name", "Rollover " + number)
                .addValue("grade", "grade_" + gradeLevel)
                .addValue("class", "class_" + gradeLevel + classSuffix)
                .addValue("year", yearId).addValue("grad", enrolYearStart + 6)
                .addValue("joined", LocalDate.of(enrolYearStart, 9, 1)));
        jdbc.update("""
                insert into account (id, student_id, grade_id, class_id, balance, frozen, version, created_at, updated_at)
                values (:id, :sid, :grade, :class, 0, 0, 1, now(3), now(3))
                """, new MapSqlParameterSource().addValue("id", "acc_it_" + number).addValue("sid", id)
                .addValue("grade", "grade_" + gradeLevel).addValue("class", "class_" + gradeLevel + classSuffix));
        return id;
    }

    private Map<String, Object> studentRow(String id) {
        return jdbc.queryForMap("select grade_id, class_id, status from student where id = :id",
                new MapSqlParameterSource("id", id));
    }

    /**
     * The hazard that nearly shipped: a freshly migrated schema has a roster in
     * its correct current grades but no rollover history, so the daily job would
     * read the current year as overdue and roll it over a second time —
     * graduating grades 8 and 12 and deactivating their logins on a database
     * that was correct a moment earlier.
     *
     * <p>Base data therefore marks the establishing year as already rolled over.
     * This test asserts the *current* year is refused, while a later year is
     * still allowed — the pair matters, because a bootstrap row that blocked
     * every year would look identical here to one that blocks only its own.</p>
     */
    @Test
    void refusesToRollOverTheYearTheSystemWasEstablishedIn() {
        String s = seedStudent("ROLLBOOT8", 8, "A", currentYearStart() - 2);

        AnnualRolloverReportDto sameYear = annualRolloverService.runIfDue("test");

        assertThat(sameYear.outcome()).isEqualTo("SKIPPED");
        assertThat(studentRow(s).get("status")).isEqualTo("ACTIVE");

        // The control: next September is genuinely due and must still run.
        assertThat(rollTo(currentYearStart() + 1).outcome()).isEqualTo("RAN");
        assertThat(studentRow(s).get("status")).isEqualTo("GRADUATED");
    }

    @Test
    void promotesWithinAStageAndCarriesTheClassSuffixForward() {
        int target = currentYearStart() + 1;
        String s = seedStudent("ROLL0001", 6, "A", currentYearStart());

        AnnualRolloverReportDto report = rollTo(target);

        assertThat(report.outcome()).isEqualTo("RAN");
        Map<String, Object> after = studentRow(s);
        assertThat(after.get("grade_id")).isEqualTo("grade_7");
        assertThat(after.get("status")).isEqualTo("ACTIVE");
        // §4: the suffix survives, the grade prefix advances.
        String code = jdbc.queryForObject("select code from class where id = :id",
                new MapSqlParameterSource("id", after.get("class_id")), String.class);
        assertThat(code).isEqualTo("7A");
    }

    /**
     * §4's worked example. A suffix is not always a single letter: some schools
     * use house or programme names, and `10-Luna` must become `11-Luna` rather
     * than `11-una` or a failed lookup. The carry-forward strips the leading
     * digits rather than assuming a one-character prefix, and this is what says
     * so.
     *
     * <p>`class_10_luna` is used because it ships in base data — `class_9_mars`
     * looks equivalent but is created by the seeder, so it does not exist in a
     * freshly migrated test schema.</p>
     */
    @Test
    void carriesANamedClassSuffixForwardIntact() {
        int target = currentYearStart() + 1;
        String id = "std_it_ROLLLUNA";
        jdbc.update("""
                insert into student (id, student_number, display_name, grade_id, class_id,
                                     enrollment_academic_year_id, graduation_year, status, joined_at,
                                     version, created_at, updated_at)
                select :id, 'ROLLLUNA', 'Rollover Luna', 'grade_10', 'class_10_luna',
                       ay.id, :grad, 'ACTIVE', :joined, 1, now(3), now(3)
                  from academic_year ay where ay.is_current = 1
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("grad", currentYearStart() + 3)
                .addValue("joined", LocalDate.of(currentYearStart(), 9, 1)));

        rollTo(target);

        String code = jdbc.queryForObject("""
                select c.code from student s join class c on c.id = s.class_id where s.id = :id
                """, new MapSqlParameterSource("id", id), String.class);
        assertThat(studentRow(id).get("grade_id")).isEqualTo("grade_11");
        assertThat(code).isEqualTo("11-Luna");
    }

    @Test
    void graduatesAtTheEndOfEachStageRatherThanPromotingAcross() {
        int target = currentYearStart() + 1;
        String junior = seedStudent("ROLL0008", 8, "A", currentYearStart() - 2);
        String senior = seedStudent("ROLL0012", 12, "A", currentYearStart() - 3);

        rollTo(target);

        // The decision this whole design rests on: grade 8 leaves, it does not
        // become grade 9 (§2.1).
        assertThat(studentRow(junior).get("status")).isEqualTo("GRADUATED");
        assertThat(studentRow(junior).get("grade_id")).isEqualTo("grade_8");
        assertThat(studentRow(senior).get("status")).isEqualTo("GRADUATED");
        assertThat(studentRow(senior).get("grade_id")).isEqualTo("grade_12");
    }

    @Test
    void theAccountSnapshotFollowsTheStudent() {
        int target = currentYearStart() + 1;
        String s = seedStudent("ROLL0010", 10, "A", currentYearStart() - 1);

        rollTo(target);

        // §3.4: an account is current state. Leaving its grade behind would put
        // the student's account outside their own teacher's scope.
        Map<String, Object> account = jdbc.queryForMap(
                "select grade_id, class_id from account where student_id = :id",
                new MapSqlParameterSource("id", s));
        Map<String, Object> student = studentRow(s);
        assertThat(account.get("grade_id")).isEqualTo("grade_11");
        assertThat(account.get("grade_id")).isEqualTo(student.get("grade_id"));
        assertThat(account.get("class_id")).isEqualTo(student.get("class_id"));
    }

    @Test
    void historicalRowsKeepTheGradeTheyWereFiledUnder() {
        int target = currentYearStart() + 1;
        String s = seedStudent("ROLL0007", 7, "A", currentYearStart() - 1);
        jdbc.update("""
                insert into service_record (id, student_id, student_name, grade_id, class_id, source_type,
                                            service_date, start_at, end_at, duration_minutes, service_category_id,
                                            status, version, created_at, updated_at)
                values ('srec_it_roll', :sid, 'Rollover ROLL0007', 'grade_7', 'class_7A', 'SELF',
                        :d, :s, :e, 60, 'cat_campus', 'APPROVED', 1, now(3), now(3))
                """, new MapSqlParameterSource().addValue("sid", s)
                .addValue("d", LocalDate.now().minusDays(3))
                .addValue("s", java.time.Instant.now().minusSeconds(7200))
                .addValue("e", java.time.Instant.now().minusSeconds(3600)));

        rollTo(target);

        // §3.4 again, the other half: rewriting this to grade_8 would falsify
        // what was true when the service happened, and move last year's totals.
        String recordGrade = jdbc.queryForObject(
                "select grade_id from service_record where id = 'srec_it_roll'",
                new MapSqlParameterSource(), String.class);
        assertThat(recordGrade).isEqualTo("grade_7");
        assertThat(studentRow(s).get("grade_id")).isEqualTo("grade_8");
    }

    @Test
    void runningTwiceForOneYearPromotesOnlyOnce() {
        int target = currentYearStart() + 1;
        String s = seedStudent("ROLL0006", 6, "B", currentYearStart());

        assertThat(rollTo(target).outcome()).isEqualTo("RAN");
        AnnualRolloverReportDto second = rollTo(target);

        // A double promotion is silent and irreversible, so the guard is the
        // single most important property here.
        assertThat(second.outcome()).isEqualTo("SKIPPED");
        assertThat(second.studentsPromoted()).isZero();
        assertThat(studentRow(s).get("grade_id")).isEqualTo("grade_7");
    }

    @Test
    void marksTheTargetYearCurrentAndReportsEmptyIntakes() {
        int target = currentYearStart() + 1;
        seedStudent("ROLL0011", 11, "A", currentYearStart() - 2);

        AnnualRolloverReportDto report = rollTo(target);

        String currentId = jdbc.queryForObject(
                "select id from academic_year where is_current = 1", new MapSqlParameterSource(), String.class);
        assertThat(currentId).isEqualTo("ay_" + target + "_" + (target + 1));
        // §3.2: grades 6 and 9 come from the roster import, so being empty right
        // after a rollover is expected and must be stated, not discovered.
        assertThat(report.emptyIntakeGrades()).contains("grade_6", "grade_9");
    }

    /**
     * The configured boundary actually moves the rollover, and moves the dates of
     * the year it creates.
     *
     * <p>Without this the setting is a knob that cannot be shown to do anything:
     * every other test here drives 1 September, which is also the default, so they
     * would all pass against a build that ignored the configuration entirely. This
     * one drives <b>25 August</b> — a date that is the *previous* academic year
     * under the default and the *new* one under a 20 August boundary — so the two
     * behaviours are distinguishable.</p>
     */
    @Test
    void aConfiguredBoundaryMovesBothTheTriggerAndTheYearItCreates() {
        // A year far enough out that no other test in this class rolls into it, so
        // this one neither depends on nor disturbs their shared target.
        int target = currentYearStart() + 5;
        String targetId = "ay_" + target + "_" + (target + 1);
        jdbc.update("delete from annual_rollover_run where academic_year_id = :id",
                new MapSqlParameterSource("id", targetId));
        // Classes reference the year, so they go first or the delete hits the
        // foreign key rather than cleaning up.
        jdbc.update("delete from class where academic_year_id = :id", new MapSqlParameterSource("id", targetId));
        jdbc.update("delete from academic_year where id = :id", new MapSqlParameterSource("id", targetId));

        // The transaction is opened explicitly by the service rather than by a
        // Spring proxy, so a hand-built instance still runs the work
        // transactionally — which is why the transaction manager is passed here.
        AnnualRolloverServiceImpl earlyStart = new AnnualRolloverServiceImpl(
                repository, runRepository, auditService, new AcademicYearCalendar("08-20"),
                transactionManager);

        // 25 August: before 1 September, so the default calendar would call this
        // the previous year and roll nothing new.
        AnnualRolloverReportDto report = earlyStart.runFor(LocalDate.of(target, 8, 25), "test-config");

        assertThat(report.academicYearId())
                .as("25 August belongs to the year starting that August once the boundary moves")
                .isEqualTo(targetId);

        var row = jdbc.queryForMap("select start_date, end_date from academic_year where id = :id",
                new MapSqlParameterSource("id", targetId));
        assertThat(row.get("start_date").toString())
                .as("the created year begins on the configured boundary, not 1 September")
                .isEqualTo(target + "-08-20");
        assertThat(row.get("end_date").toString())
                .as("and ends the day before the next one begins, with no gap")
                .isEqualTo((target + 1) + "-08-19");
    }
}
