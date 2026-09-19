package cn.timebank.dataexchange.application;

import cn.timebank.common.error.ApiException;
import cn.timebank.dataexchange.application.dto.DataExchangeRequests.CreateExportRequest;
import cn.timebank.dataexchange.application.dto.DataExchangeRequests.ExportFilters;
import cn.timebank.dataexchange.application.dto.ExportTypeDto;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.dataexchange.infrastructure.JobRepository;
import cn.timebank.file.application.dto.DownloadResult;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2's exit criteria (`Docs/optimization/11-DataImportExportOptimization.md`
 * §3.2) — the ones that are only true or false against real data: scope resolving
 * to <em>students</em> rather than to a grade snapshot, the year filter excluding
 * students who were not enrolled, and the §7.3 refusal.
 *
 * <p>Every fixture here is built with its own grades, classes and academic years
 * rather than leaning on the seed, because the properties under test are about
 * boundaries — a student in one grade and not another, a record filed under a
 * different grade than the student holds now — and a fixture that happens not to
 * straddle a boundary would pass without proving anything.</p>
 */
class ExportScopeIT extends AbstractIntegrationTest {

    @Autowired
    private ExportService exportService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    // 2014/2016 rather than years near the present: `academic_year.code` is
    // unique and base data already seeds the current year and its predecessor, so
    // a fixture using 2026-2027 collides, gets swallowed by INSERT IGNORE, and
    // surfaces later as an unrelated foreign-key failure on `student`.
    private static final String YEAR_PAST = "ay_it_scope_past";
    private static final int YEAR_PAST_START = 2014;
    private static final String YEAR_CURRENT = "ay_it_scope_current";
    private static final int YEAR_CURRENT_START = 2016;
    private static final String GRADE_A = "grade_7";
    private static final String GRADE_B = "grade_8";
    private static final String GRADE_ENTRY = "grade_6";

    @BeforeEach
    void resetFixture() {
        jdbc.update("delete from teacher_scope_assignment where id like 'tsa_scope_it%'", noArgs());
        // teacher_scope_assignment has a foreign key to `user`, so the bound
        // teacher has to be a real row rather than an id invented by the fixture.
        insertUser("usr_scope_it_teacher", "scope_it_teacher");
        insertUser("usr_scope_it_unbound", "scope_it_unbound");
        jdbc.update("delete from service_record where id like 'sr_scope_it%'", noArgs());
        jdbc.update("delete from student where id like 'stu_scope_it%'", noArgs());
        jdbc.update("delete from class where id like 'cls_scope_it%'", noArgs());
        jdbc.update("update academic_year set is_current = 0 where is_current = 1", noArgs());
        upsertYear(YEAR_PAST, YEAR_PAST_START);
        upsertYear(YEAR_CURRENT, YEAR_CURRENT_START);
        jdbc.update("update academic_year set is_current = 1 where id = :id",
                new MapSqlParameterSource("id", YEAR_CURRENT));
        insertClass("cls_scope_it_7a", "7-ScopeIT", GRADE_A);
        insertClass("cls_scope_it_8a", "8-ScopeIT", GRADE_B);
        insertClass("cls_scope_it_6a", "6-ScopeIT", GRADE_ENTRY);
    }

    /**
     * Exit criterion 3: a teacher bound to one grade who asks for "All grades"
     * gets only their grade — the filter defaulting to All must not widen what
     * scope already decided.
     */
    @Test
    void aGradeBoundTeacherAskingForAllGradesGetsOnlyTheirGrade() throws Exception {
        insertStudent("stu_scope_it_mine", "S-MINE", "Mine Student", GRADE_A, "cls_scope_it_7a", YEAR_CURRENT, 2029);
        insertStudent("stu_scope_it_other", "S-OTHER", "Other Student", GRADE_B, "cls_scope_it_8a", YEAR_CURRENT, 2028);
        bindGrade("usr_scope_it_teacher", GRADE_A);

        String csv = exportCsv(teacherAuth("usr_scope_it_teacher"), "studentList", ExportFilters.none());

        assertThat(csv).contains("S-MINE");
        assertThat(csv).as("a grade the teacher is not bound to must not appear").doesNotContain("S-OTHER");
    }

    /**
     * Exit criterion 4, the one that distinguishes student-scoping from
     * snapshot-scoping — and the whole behavioural reason phase 2 exists.
     *
     * <p>The student is in grade 8 today and their teacher is bound to grade 8,
     * but the record was filed while they were in grade 7, so it carries a grade
     * 7 snapshot. Filtering records by snapshot grade would silently drop it.
     * A test that used a student whose snapshot and live grade agree could not
     * tell the two implementations apart.</p>
     */
    @Test
    void aRecordFiledUnderAnEarlierGradeStillReachesTheStudentsPresentTeacher() throws Exception {
        insertStudent("stu_scope_it_moved", "S-MOVED", "Moved Student", GRADE_B, "cls_scope_it_8a", YEAR_PAST, 2028);
        // Filed last year, under the grade they held then — not the grade they hold now.
        insertServiceRecord("sr_scope_it_old", "stu_scope_it_moved", GRADE_A, "cls_scope_it_7a",
                LocalDate.of(2026, 3, 14), 120, 200, "APPROVED");
        bindGrade("usr_scope_it_teacher", GRADE_B);

        String csv = exportCsv(teacherAuth("usr_scope_it_teacher"), "serviceRecords", ExportFilters.none());

        assertThat(csv)
                .as("the record belongs to a student in scope, so its own grade snapshot is irrelevant to inclusion")
                .contains("S-MOVED");
    }

    /** Exit criterion 5: no grade binding is a refusal with §7.3's message, and no file at all. */
    @Test
    void aTeacherWithNoGradeBindingIsRefusedRatherThanGivenAnEmptyFile() {
        insertStudent("stu_scope_it_any", "S-ANY", "Any Student", GRADE_A, "cls_scope_it_7a", YEAR_CURRENT, 2029);

        assertThatThrownBy(() -> exportService.create(
                new CreateExportRequest("studentList", null), teacherAuth("usr_scope_it_unbound")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not been assigned any grade");

        assertThat(jobRepository.findAll()).as("no job row, so no file was ever produced")
                .noneMatch(j -> "usr_scope_it_unbound".equals(j.getCreatedBy()));
    }

    /**
     * Exit criteria 6 and 7 together: selecting a past year derives that year's
     * grade, and a student who was not enrolled then is absent rather than
     * present with blanks.
     */
    @Test
    void aPastYearDerivesThatYearsGradeAndExcludesStudentsWhoWereNotThereYet() throws Exception {
        // Enrolled 2024 at grade 6, so in 2024-2025 they were grade 6 and are grade 8 now.
        // Enrolled 2014 at grade 6, so grade 8 now (2016) and grade 6 back then.
        insertStudent("stu_scope_it_veteran", "S-VETERAN", "Veteran Student", GRADE_B, "cls_scope_it_8a",
                YEAR_PAST, 2017);
        // Enrolled this year at grade 6, so they did not exist in the school in 2014-2015.
        insertStudent("stu_scope_it_newcomer", "S-NEWCOMER", "New Student", GRADE_ENTRY, "cls_scope_it_6a",
                YEAR_CURRENT, 2019);

        String csv = exportCsv(adminAuth(), "studentList",
                new ExportFilters(YEAR_PAST, null, null, null));

        assertThat(csv).contains("S-VETERAN");
        assertThat(csv).as("§5.1: not enrolled that year ⇒ absent, not a blank grade").doesNotContain("S-NEWCOMER");

        String veteranRow = rowContaining(csv, "S-VETERAN");
        assertThat(veteranRow)
                .as("the derived grade for 2014-2015, not the grade 8 they hold today")
                .contains("Grade 6")
                .doesNotContain("Grade 8");

        // The control: the same export with no year selected shows present state,
        // so the same student reads as grade 8. Without this, "Grade 6" could just
        // as well mean the derivation ran on the wrong student.
        String presentCsv = exportCsv(adminAuth(), "studentList", ExportFilters.none());
        assertThat(rowContaining(presentCsv, "S-VETERAN")).contains("Grade 8");
        assertThat(presentCsv).as("with no year filter the newcomer is in scope again").contains("S-NEWCOMER");
    }

    /** §2.6.1: a filter the type does not declare is refused, never accepted and dropped. */
    @Test
    void aFilterTheTypeDoesNotDeclareIsRefused() {
        assertThatThrownBy(() -> exportService.create(
                new CreateExportRequest("studentList", new ExportFilters(null, null, null, "usr_someone")),
                adminAuth()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("filters failed validation");

        // The control: the same call without the undeclared filter is accepted,
        // so the refusal is about that field and not about the request shape.
        assertThat(exportService.create(new CreateExportRequest("studentList", ExportFilters.none()), adminAuth()))
                .isNotNull();
    }

    /** A filter naming something that does not exist is a 404, not a silently wider file. */
    @Test
    void anUnknownAcademicYearIsRejected() {
        assertThatThrownBy(() -> exportService.create(
                new CreateExportRequest("studentList", new ExportFilters("ay_does_not_exist", null, null, null)),
                adminAuth()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Academic year not found");
    }

    /** §2.6.1's catalogue, as the client sees it: only types the caller holds, each with its own filter set. */
    @Test
    void theTypeCatalogueIsFilteredToWhatTheCallerHolds() {
        List<ExportTypeDto> forAdmin = exportService.availableTypes(adminAuth());

        assertThat(forAdmin).extracting(ExportTypeDto::key)
                .containsExactly("studentList", "serviceRecords", "studentCompletion",
                        "studentParticipation", "projects", "reviewEfficiency", "reviewHistory");
        assertThat(forAdmin).filteredOn(t -> t.key().equals("reviewHistory")).singleElement()
                .satisfies(t -> assertThat(t.filters())
                        .as("Review History is filtered by who decided, never by class")
                        .containsExactly("academicYearId", "reviewerId"));
        assertThat(forAdmin).filteredOn(ExportTypeDto::available).extracting(ExportTypeDto::key)
                .as("only the two phase-2 types have a query today")
                .containsExactly("studentList", "serviceRecords");
    }

    // ── fixture ──────────────────────────────────────────────────────────

    private String exportCsv(Authentication auth, String type, ExportFilters filters) throws Exception {
        JobDto job = exportService.create(new CreateExportRequest(type, filters), auth);
        awaitTerminal(job.id());
        assertThat(jobRepository.findById(job.id()).orElseThrow().getStatus())
                .as("export job %s must succeed", job.id()).isEqualTo("SUCCEEDED");
        String url = exportService.issueDownloadUrl(job.id(), adminAuth());
        DownloadResult result = exportService.download(url.substring(url.indexOf("token=") + 6));
        return new String(result.content().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String rowContaining(String csv, String needle) {
        return csv.lines().filter(line -> line.contains(needle)).findFirst()
                .orElseThrow(() -> new AssertionError("No row containing " + needle + " in:\n" + csv));
    }

    private void awaitTerminal(String jobId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
        while (Instant.now().isBefore(deadline)) {
            String status = jobRepository.findById(jobId).orElseThrow().getStatus();
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                return;
            }
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Export job " + jobId + " did not finish in time");
    }

    private void upsertYear(String id, int startYear) {
        jdbc.update("""
                insert into academic_year (id, code, start_date, end_date, is_current, created_at, updated_at)
                values (:id, :code, :start, :end, 0, now(3), now(3))
                on duplicate key update start_date = :start, end_date = :end
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("code", startYear + "-" + (startYear + 1))
                .addValue("start", LocalDate.of(startYear, 9, 1))
                .addValue("end", LocalDate.of(startYear + 1, 8, 31)));
    }

    private void insertClass(String id, String code, String gradeId) {
        jdbc.update("""
                insert ignore into class (id, code, name, grade_id, academic_year_id, created_at, updated_at)
                values (:id, :code, :name, :grade, :year, now(3), now(3))
                """, new MapSqlParameterSource().addValue("id", id).addValue("code", code)
                .addValue("name", code.replaceFirst("^\\d+", "")).addValue("grade", gradeId)
                .addValue("year", YEAR_CURRENT));
    }

    private void insertStudent(String id, String number, String name, String gradeId, String classId,
                                String enrolmentYearId, int graduationYear) {
        jdbc.update("""
                insert into student (id, student_number, display_name, grade_id, class_id,
                                     enrollment_academic_year_id, graduation_year, status, joined_at,
                                     version, created_at, updated_at)
                values (:id, :number, :name, :grade, :class, :year, :graduation, 'ACTIVE', :joined,
                        1, now(3), now(3))
                """, new MapSqlParameterSource().addValue("id", id).addValue("number", number)
                .addValue("name", name).addValue("grade", gradeId).addValue("class", classId)
                .addValue("year", enrolmentYearId).addValue("graduation", graduationYear)
                .addValue("joined", LocalDate.of(2024, 9, 1)));
    }

    private void insertServiceRecord(String id, String studentId, String gradeId, String classId,
                                      LocalDate serviceDate, int minutes, Integer creditedUnits, String status) {
        jdbc.update("""
                insert into service_record (id, student_id, student_name, grade_id, class_id, source_type,
                                            service_date, start_at, end_at, service_category_id,
                                            duration_minutes, status, credited_units,
                                            includes_travel_time, has_anomaly, version, created_at, updated_at)
                values (:id, :student, 'Snapshot Name', :grade, :class, 'SELF',
                        :date, :startAt, :endAt, :category,
                        :minutes, :status, :credited, 0, 0, 1, now(3), now(3))
                """, new MapSqlParameterSource().addValue("id", id).addValue("student", studentId)
                .addValue("grade", gradeId).addValue("class", classId).addValue("date", serviceDate)
                .addValue("startAt", serviceDate.atTime(9, 0))
                .addValue("endAt", serviceDate.atTime(9, 0).plusMinutes(minutes))
                .addValue("category", anyServiceCategoryId())
                .addValue("minutes", minutes).addValue("status", status).addValue("credited", creditedUnits));
    }

    /** Any seeded category — this fixture is about scope, and which category a record carries is beside the point. */
    private String anyServiceCategoryId() {
        return jdbc.queryForObject("select id from service_category order by id limit 1", noArgs(), String.class);
    }

    private void insertUser(String id, String username) {
        jdbc.update("""
                insert ignore into user (id, username, display_name, status, preferred_language,
                                          must_change_password, failed_login_attempts, version, created_at, updated_at)
                values (:id, :username, :display, 'ACTIVE', 'zh-CN', 0, 0, 1, now(3), now(3))
                """, new MapSqlParameterSource().addValue("id", id).addValue("username", username)
                .addValue("display", "Display " + username));
    }

    private void bindGrade(String userId, String gradeId) {
        jdbc.update("""
                insert into teacher_scope_assignment (id, user_id, resource_type, scope_type, scope_value, created_at)
                values (:id, :user, 'students', 'GRADE', :grade, now(3))
                """, new MapSqlParameterSource()
                .addValue("id", "tsa_scope_it_" + System.nanoTime() % 1_000_000_000L)
                .addValue("user", userId).addValue("grade", gradeId));
    }

    private MapSqlParameterSource noArgs() {
        return new MapSqlParameterSource();
    }

    private Authentication adminAuth() {
        return authFor("usr_scope_it_admin", "admin");
    }

    private Authentication teacherAuth(String userId) {
        return authFor(userId, "teacher");
    }

    /**
     * The permission codes matter here, not just the role: {@code availableTypes}
     * filters on granted authorities, so a principal carrying an empty permission
     * set would return an empty catalogue and the assertion would "pass" against
     * nothing. An admin gets all seven; a teacher gets the six of §5.3.
     */
    private Authentication authFor(String userId, String role) {
        Set<String> exportPermissions = new java.util.LinkedHashSet<>(Set.of(
                "exports.studentList", "exports.serviceRecords", "exports.studentCompletion",
                "exports.studentParticipation", "exports.projects", "exports.reviewHistory"));
        if (!"teacher".equals(role)) {
            exportPermissions.add("exports.reviewEfficiency");
        }
        AuthenticatedUserView view = new AuthenticatedUserView(
                userId, userId, "hash", "Display " + userId, true, false, "zh-CN",
                Set.of(role), exportPermissions, null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
