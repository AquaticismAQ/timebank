package cn.timebank.lifecycle.application;

import cn.timebank.audit.application.AuditService;
import cn.timebank.lifecycle.infrastructure.AnnualRolloverRepository;
import cn.timebank.lifecycle.infrastructure.AnnualRolloverRunRepository;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * What survives when a rollover fails part-way.
 *
 * <p>The rollover is one transaction, so a failure anywhere rolls the student
 * changes back and there is no half-promoted school — that half is asserted here
 * too, because it is the property everything else rests on. The question this
 * class exists for is the other half: <b>does the failure leave a trace</b>.
 * `V30`'s own comment says keeping a failed attempt visible "is the point of
 * having the table at all", and an operator arriving the morning after a failed
 * 1 September needs to find something other than silence.</p>
 */
class AnnualRolloverFailureIT extends AbstractIntegrationTest {

    /**
     * A spy on the real bean, not a hand-built instance.
     *
     * <p>This matters more than it looks. An earlier version of this test built
     * the service with {@code new}, which bypasses Spring's transactional proxy
     * entirely — every repository call then auto-committed, the graduated
     * students stayed graduated after the "failure", and the test appeared to
     * prove the rollover was not atomic. It proved only that a hand-built object
     * has no transaction. Failure behaviour has to be driven through the
     * container-managed bean or it is measuring nothing.</p>
     */
    @MockitoSpyBean
    private AnnualRolloverRepository repository;
    @Autowired
    private AnnualRolloverRunRepository runRepository;
    @Autowired
    private AuditService auditService;
    @Autowired
    private AnnualRolloverServiceImpl service;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void clearRuns() {
        reset(repository);
        jdbc.update("delete from annual_rollover_run where triggered_by <> 'bootstrap'",
                new MapSqlParameterSource());
    }

    /**
     * A rollover that dies half way — the database going away mid-run, a
     * constraint firing, the process being killed — must leave the roster
     * untouched and the attempt recorded.
     */
    @Test
    void aFailedRolloverLeavesTheRosterUntouchedAndRecordsTheAttempt() {
        int target = currentYearStart() + 7;
        String targetId = "ay_" + target + "_" + (target + 1);

        seedStudents("a");
        Map<String, String> before = gradesByStudent();
        assertThat(before).as("fixture must have students to promote").isNotEmpty();

        // Fail at the point where students have already started moving: the exit
        // grades are graduated before promotion begins, so a non-atomic
        // implementation leaves visible damage here.
        doThrow(new IllegalStateException("simulated database failure mid-rollover"))
                .when(repository).promoteGrade(anyInt(), anyString());

        // The type, not the message, is what @Repository's exception translation
        // rewrites — so assert on the message.
        assertThatThrownBy(() -> service.runFor(LocalDate.of(target, 9, 1), "failure-test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated database failure");

        assertThat(gradesByStudent())
                .as("one transaction: a failure part-way must leave no student moved and none graduated")
                .isEqualTo(before);

        List<Map<String, Object>> runs = jdbc.queryForList(
                "select status, error_message from annual_rollover_run where academic_year_id = :id",
                new MapSqlParameterSource("id", targetId));
        assertThat(runs)
                .as("a failed attempt must be findable afterwards — V30 exists to keep it visible, "
                        + "and an operator arriving the next morning has nothing else to go on")
                .hasSize(1);
        assertThat(runs.get(0).get("status")).isEqualTo("FAILED");
        assertThat((String) runs.get(0).get("error_message")).contains("simulated database failure");
    }

    /** Retryable: the year is not marked done, so a later run still has work to do. */
    @Test
    void aFailedYearIsNotTreatedAsAlreadyDone() {
        int target = currentYearStart() + 8;
        String targetId = "ay_" + target + "_" + (target + 1);
        seedStudents("b");

        doThrow(new IllegalStateException("simulated failure"))
                .when(repository).promoteGrade(anyInt(), anyString());
        assertThatThrownBy(() -> service.runFor(LocalDate.of(target, 9, 1), "failure-test"))
                .isInstanceOf(RuntimeException.class);
        reset(repository);

        assertThat(repository.hasSucceededFor(targetId))
                .as("a failed run must never block the retry that fixes it")
                .isFalse();
    }

    /** One student in an exit grade and one in a promotable grade, so a partial run would be visible. */
    private void seedStudents(String suffix) {
        String yearId = jdbc.queryForObject(
                "select id from academic_year where is_current = 1 order by start_date desc limit 1",
                new MapSqlParameterSource(), String.class);
        String classId = jdbc.queryForObject(
                "select id from class order by id limit 1", new MapSqlParameterSource(), String.class);
        for (String grade : List.of("grade_8", "grade_7")) {
            jdbc.update("""
                    insert into student (id, student_number, display_name, grade_id, class_id,
                                         enrollment_academic_year_id, graduation_year, status, joined_at,
                                         version, created_at, updated_at)
                    values (:id, :num, :name, :grade, :class, :year, 2030, 'ACTIVE', :joined, 1, now(3), now(3))
                    """, new MapSqlParameterSource()
                    .addValue("id", "stu_fail_it_" + grade + "_" + suffix)
                    .addValue("num", "FAIL-" + grade + "-" + suffix)
                    .addValue("name", "Failure Fixture " + grade)
                    .addValue("grade", grade).addValue("class", classId).addValue("year", yearId)
                    .addValue("joined", LocalDate.of(2024, 9, 1)));
        }
    }

    private Map<String, String> gradesByStudent() {
        return jdbc.query("select id, grade_id, status from student order by id",
                new MapSqlParameterSource(), rs -> {
                    Map<String, String> out = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        out.put(rs.getString("id"), rs.getString("grade_id") + "/" + rs.getString("status"));
                    }
                    return out;
                });
    }

    private int currentYearStart() {
        LocalDate today = LocalDate.now();
        return today.getMonthValue() >= 9 ? today.getYear() : today.getYear() - 1;
    }
}
