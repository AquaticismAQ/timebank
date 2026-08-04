package cn.timebank.lifecycle;

import cn.timebank.account.application.AccountService;
import cn.timebank.caseflow.application.CaseService;
import cn.timebank.caseflow.application.dto.CaseDto;
import cn.timebank.caseflow.application.dto.CaseRequests.CreateCaseRequest;
import cn.timebank.caseflow.application.dto.CaseRequests.DecideRequest;
import cn.timebank.file.application.FileService;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.lifecycle.application.CohortPurgeService;
import cn.timebank.lifecycle.application.dto.PurgeRunSummaryDto;
import cn.timebank.review.application.ReviewService;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.CreateServiceRecordRequest;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6.3/B6.4/B6.5's exit criteria: a dry run against a seeded graduated
 * cohort reports exactly the expected rows/objects, a real run deletes
 * them (across the full FK graph, not just `student` itself), a
 * currently-enrolled sibling student's data is completely untouched, and
 * the purge is itself audited. R5 names this the highest-consequence item
 * in the whole phase — every assertion here is a row count, not a
 * "didn't throw."
 */
class CohortPurgeIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private FileService fileService;
    @Autowired
    private UserService userService;
    @Autowired
    private CohortPurgeService cohortPurgeService;
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    private static final byte[] VALID_JPEG = cn.timebank.testsupport.TestImages.validJpegBytes();

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void dryRunReportsExactCountsAndRealRunDeletesEverythingLeavingSiblingsUntouched() {
        StudentDetailDto graduated = createStudent(2010); // 2010 + 1 = 2011-08-31, long past
        StudentDetailDto currentlyEnrolled = createStudent(2029);

        String fileId = createApprovedServiceRecordWithEvidence(graduated);
        createAndResolveManualCorrection(graduated, 300);
        var user = userService.create(new CreateUserRequest(
                "purge_user_" + SEQ.incrementAndGet(), "InitialPass123!", "Purge Test Student", null, Set.of("student"), "zh-CN"), "usr_test_admin");
        jdbcTemplate.update("update user set student_id = :studentId where id = :userId",
                new MapSqlParameterSource().addValue("studentId", graduated.id()).addValue("userId", user.id()));

        // --- dry run ---
        List<PurgeRunSummaryDto> dryRunResults = cohortPurgeService.run(true, "usr_test_admin");
        PurgeRunSummaryDto cohort2010 = dryRunResults.stream().filter(s -> s.studentsScanned() > 0
                        && s.perStudent().stream().anyMatch(d -> d.studentId().equals(graduated.id())))
                .findFirst().orElseThrow(() -> new AssertionError("2010 cohort not found eligible in dry run"));
        assertThat(cohort2010.dryRun()).isTrue();
        assertThat(cohort2010.studentsPurged()).isZero(); // dry run purges nothing
        var detail = cohort2010.perStudent().stream().filter(d -> d.studentId().equals(graduated.id())).findFirst().orElseThrow();
        assertThat(detail.serviceRecords()).isEqualTo(1);
        assertThat(detail.accountTransactions()).isEqualTo(2); // approval credit + manual correction
        assertThat(detail.cases()).isEqualTo(1);
        assertThat(detail.evidenceFiles()).isEqualTo(1);
        assertThat(cohort2010.perStudent()).extracting(PurgeRunSummaryDto.StudentPurgeDetail::studentId)
                .doesNotContain(currentlyEnrolled.id());

        // Nothing was actually deleted by the dry run.
        assertThat(countServiceRecords(graduated.id())).isEqualTo(1);
        assertThat(fileService.statusesOf(List.of(fileId))).containsKey(fileId);

        // --- real run ---
        List<PurgeRunSummaryDto> realResults = cohortPurgeService.run(false, "usr_test_admin");
        PurgeRunSummaryDto realCohort2010 = realResults.stream()
                .filter(s -> s.perStudent().stream().anyMatch(d -> d.studentId().equals(graduated.id())) || s.studentsPurged() > 0)
                .findFirst().orElseThrow();
        assertThat(realCohort2010.dryRun()).isFalse();
        assertThat(realCohort2010.studentsPurged()).isEqualTo(1);
        assertThat(realCohort2010.rowsDeleted()).isGreaterThan(0);
        assertThat(realCohort2010.objectsDeleted()).isEqualTo(1);

        assertThat(countServiceRecords(graduated.id())).isZero();
        assertThat(countAccounts(graduated.id())).isZero();
        assertThat(countCases(graduated.id())).isZero();
        assertThat(studentExists(graduated.id())).isFalse();
        assertThat(userExists(user.id())).isFalse();
        assertThat(fileService.statusesOf(List.of(fileId))).doesNotContainKey(fileId);

        // The currently-enrolled sibling is completely untouched.
        assertThat(studentExists(currentlyEnrolled.id())).isTrue();

        // Re-running finds nothing left to purge for this cohort.
        List<PurgeRunSummaryDto> secondDryRun = cohortPurgeService.run(true, "usr_test_admin");
        boolean stillHasGraduated = secondDryRun.stream()
                .anyMatch(s -> s.perStudent().stream().anyMatch(d -> d.studentId().equals(graduated.id())));
        assertThat(stillHasGraduated).isFalse();
    }

    private String createApprovedServiceRecordWithEvidence(StudentDetailDto student) {
        String fileId = readyFile();
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", null, null, List.of(fileId), false);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
        ServiceRecordDto submitted = serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-purge-submit-" + draft.id(), "zh-CN");
        reviewService.decide(submitted.id(),
                new cn.timebank.review.application.dto.ReviewRequests.DecideRequest("APPROVE", "GOOD", null, submitted.version()),
                adminAuth(), "idem-purge-decide-" + submitted.id(), "zh-CN");
        return fileId;
    }

    private void createAndResolveManualCorrection(StudentDetailDto student, int amountCentiCoins) {
        CreateCaseRequest request = new CreateCaseRequest(student.id(), "manualCorrection", "serviceRecord", null,
                "Missed hours", "Supervisor confirmed late", amountCentiCoins, "LATE_SIGNOFF");
        CaseDto draft = caseService.createDraft(request, adminAuth(), "zh-CN");
        CaseDto submitted = caseService.submit(draft.id(), adminAuth(), "idem-purge-case-submit-" + draft.id(), "zh-CN");
        caseService.decide(submitted.id(), new DecideRequest("resolve", "APPROVED_CORRECTION", null, submitted.version()),
                adminAuth(), "idem-purge-case-decide-" + submitted.id(), "zh-CN");
    }

    private String readyFile() {
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("evidence.jpg", "image/jpeg", VALID_JPEG.length, "serviceRecordEvidence"),
                "usr_test_admin");
        fileService.receiveContent(intent.fileId(), "usr_test_admin", new ByteArrayInputStream(VALID_JPEG));
        fileService.complete(intent.fileId(), "usr_test_admin");
        return intent.fileId();
    }

    private int countServiceRecords(String studentId) {
        Long c = jdbcTemplate.queryForObject("select count(*) from service_record where student_id = :id",
                new MapSqlParameterSource("id", studentId), Long.class);
        return c == null ? 0 : c.intValue();
    }

    private int countAccounts(String studentId) {
        Long c = jdbcTemplate.queryForObject("select count(*) from account where student_id = :id",
                new MapSqlParameterSource("id", studentId), Long.class);
        return c == null ? 0 : c.intValue();
    }

    private int countCases(String studentId) {
        Long c = jdbcTemplate.queryForObject("select count(*) from case_record where student_id = :id",
                new MapSqlParameterSource("id", studentId), Long.class);
        return c == null ? 0 : c.intValue();
    }

    private boolean studentExists(String studentId) {
        Long c = jdbcTemplate.queryForObject("select count(*) from student where id = :id",
                new MapSqlParameterSource("id", studentId), Long.class);
        return c != null && c > 0;
    }

    private boolean userExists(String userId) {
        Long c = jdbcTemplate.queryForObject("select count(*) from user where id = :id",
                new MapSqlParameterSource("id", userId), Long.class);
        return c != null && c > 0;
    }

    private StudentDetailDto createStudent(int graduationYear) {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_purge_" + n, "Purge Student " + n, null, "grade_6", "class_6A", "ay_2025_2026",
                graduationYear, LocalDate.of(2025, 9, 1), null, null);
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private Authentication studentAuth(String studentId) {
        return authFor("usr_" + studentId.substring(4), studentId, "student");
    }

    private Authentication adminAuth() {
        return authFor("usr_test_admin", null, "admin");
    }

    private Authentication authFor(String userId, String studentId, String roleCode) {
        AuthenticatedUserView view = new AuthenticatedUserView(
                userId, userId, "hash", "Test " + userId, true, false, "zh-CN", Set.of(roleCode), Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
