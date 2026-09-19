package cn.timebank.dataexchange.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.common.error.ApiException;
import cn.timebank.dataexchange.application.dto.DataExchangeRequests.CreateExportRequest;
import cn.timebank.dataexchange.application.dto.ImportErrorReportDto;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.dataexchange.domain.Job;
import cn.timebank.dataexchange.infrastructure.JobRepository;
import cn.timebank.file.application.dto.DownloadResult;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B5.9/B5.10's exit criteria against real MySQL: an import validates with a
 * row-level error report, commits exactly the valid rows once each,
 * re-importing the identical file is rejected outright (never reaches
 * commit), and the checkpoint-skip mechanism a reclaimed commit run relies
 * on does not re-credit already-committed rows. Exports complete
 * asynchronously (never on the request thread) and produce downloadable
 * content. What is *not* proven here, honestly: a true 500k-row run and
 * its effect on p95 latency under concurrent load — that is G4/B6.10
 * territory, not this test.
 */
class ImportExportIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private ImportService importService;
    @Autowired
    private ImportJobRunner importJobRunner;
    @Autowired
    private ExportService exportService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private cn.timebank.servicerecord.application.ServiceRecordService serviceRecordService;
    @Autowired
    private cn.timebank.review.application.ReviewService reviewService;
    @Autowired
    private cn.timebank.file.application.FileService fileService;
    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbcTemplate;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void importValidateProducesCorrectCountsAndErrorReport() {
        StudentDetailDto s1 = createStudent();
        StudentDetailDto s2 = createStudent();
        JobDto job = createAndValidateImport(s1, s2);

        assertThat(job.status()).isEqualTo("VALIDATED");
        assertThat(job.totalRows()).isEqualTo(5);
        assertThat(job.validRows()).isEqualTo(2);

        ImportErrorReportDto errors = importService.errors(job.id(), adminAuth());
        assertThat(errors.totalErrors()).isEqualTo(3);
        assertThat(errors.errors()).extracting(ImportErrorReportDto.RowError::code)
                .containsExactlyInAnyOrder("STUDENT_NOT_FOUND", "INVALID_DATE", "INVALID_DURATION");
    }

    @Test
    void importCommitCreditsExactlyTheValidRowsOnce() {
        StudentDetailDto s1 = createStudent();
        StudentDetailDto s2 = createStudent();
        JobDto validated = createAndValidateImport(s1, s2);

        importService.commit(validated.id(), "usr_test_admin", "idem-import-commit-" + validated.id());
        awaitStatus(validated.id(), "COMMITTED");

        // duration 120 rounded to 120 -> 120*100/60 = 200 centi-coins; duration 60 -> 100 centi-coins.
        assertThat(accountService.getBalanceForStudent(s1.id())).isEqualTo(200);
        assertThat(accountService.getBalanceForStudent(s2.id())).isEqualTo(100);
        // The reported count, not just the ledger effect — a prior bug left
        // this inflated (misreading the validate phase's "2 valid rows" as
        // "2 already committed") even though the actual credits were correct.
        JobDto committedJob = jobRepository.findById(validated.id()).map(this::toDto).orElseThrow();
        assertThat(committedJob.validRows()).isEqualTo(2);
    }

    @Test
    void reimportingTheIdenticalFileIsRejectedAfterCommit() {
        StudentDetailDto s1 = createStudent();
        StudentDetailDto s2 = createStudent();
        byte[] bytes = csvBytes(s1, s2);
        JobDto job = importService.create("serviceRecordsHistorical",
                new MockMultipartFile("file", "ledger.csv", "text/csv", bytes), "usr_test_admin");
        importService.validate(job.id(), "usr_test_admin");
        awaitStatus(job.id(), "VALIDATED");
        importService.commit(job.id(), "usr_test_admin", "idem-reimport-commit-" + job.id());
        awaitStatus(job.id(), "COMMITTED");

        assertThatThrownBy(() -> importService.create("serviceRecordsHistorical",
                new MockMultipartFile("file", "ledger.csv", "text/csv", bytes), "usr_test_admin"))
                .isInstanceOf(ApiException.class);

        // Only the original commit's credits exist — no second file was ever accepted to double them.
        assertThat(accountService.getBalanceForStudent(s1.id())).isEqualTo(200);
    }

    @Test
    void aResumedCommitSkipsAlreadyCommittedRowsAndDoesNotDoubleCredit() {
        StudentDetailDto s1 = createStudent();
        StudentDetailDto s2 = createStudent();
        JobDto validated = createAndValidateImport(s1, s2);
        importService.commit(validated.id(), "usr_test_admin", "idem-resume-commit-" + validated.id());
        awaitStatus(validated.id(), "COMMITTED");
        assertThat(accountService.getBalanceForStudent(s1.id())).isEqualTo(200);
        assertThat(accountService.getBalanceForStudent(s2.id())).isEqualTo(100);

        // Simulate a reclaim: the job's checkpoint/validRows already reflect
        // "every row processed" from the completed run (never reset by
        // succeed()); flip status back to COMMITTING and re-invoke the
        // runner directly, exactly what JobReclaimSweep does for a job whose
        // lease expired mid-run. The resume-skip arithmetic must recognize
        // every row as already handled and credit nothing further.
        Job job = jobRepository.findById(validated.id()).orElseThrow();
        job.setStatus("COMMITTING");
        jobRepository.saveAndFlush(job);

        importJobRunner.commitAsync(validated.id(), "usr_test_admin");
        awaitStatus(validated.id(), "COMMITTED");

        assertThat(accountService.getBalanceForStudent(s1.id())).isEqualTo(200);
        assertThat(accountService.getBalanceForStudent(s2.id())).isEqualTo(100);
    }

    @Test
    void exportsCompleteAsynchronouslyAndProduceDownloadableContent() throws Exception {
        StudentDetailDto s1 = createStudent();
        createAnApprovedServiceRecord(s1, 60);

        // `studentList` is the post-V31 key for the roster export (was
        // `students`). It runs end to end here rather than merely being
        // accepted, because the runner still dispatches on the old name and
        // ExportServiceImpl maps between the two — a rename applied to the
        // request but not to the stored resource type would leave this job
        // FAILED, which only a completed run can tell us.
        JobDto rosterExport = exportService.create(new CreateExportRequest("studentList"), "usr_test_admin");
        awaitTerminal(rosterExport.id());
        JobDto finishedRoster = jobRepository.findById(rosterExport.id()).map(this::toDto).orElseThrow();
        assertThat(finishedRoster.status()).isEqualTo("SUCCEEDED");
        assertThat(finishedRoster.finishedAt()).isNotNull();

        JobDto rawExport = exportService.create(new CreateExportRequest("serviceRecords"), "usr_test_admin");
        awaitTerminal(rawExport.id());
        JobDto finishedRaw = jobRepository.findById(rawExport.id()).map(this::toDto).orElseThrow();
        assertThat(finishedRaw.status()).isEqualTo("SUCCEEDED");

        String url = exportService.issueDownloadUrl(rawExport.id(), adminAuth());
        String token = url.substring(url.indexOf("token=") + "token=".length());
        DownloadResult result = exportService.download(token);
        String content = new String(result.content().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).startsWith("id,studentId,studentName,serviceDate,durationMinutes,creditedUnits,status");
        assertThat(content).contains(s1.id());

        // The roster file is downloadable too, and carries the student the
        // fixture created — the studentList key really reaches the roster
        // query and not some other writer.
        String rosterUrl = exportService.issueDownloadUrl(rosterExport.id(), adminAuth());
        DownloadResult roster = exportService.download(
                rosterUrl.substring(rosterUrl.indexOf("token=") + "token=".length()));
        assertThat(new String(roster.content().readAllBytes(), StandardCharsets.UTF_8)).contains(s1.studentNumber());

        // F-9: an export is the one operation that takes student data out of
        // the system, so asking for one has to be answerable afterwards. The
        // permission is asserted alongside the action because the whole point
        // of V31's split is that "who was allowed to do this" now has a
        // specific answer rather than a blanket `exports.create`.
        assertThat(auditActionsFor(rosterExport.id()))
                .as("requesting an export must leave an audit row")
                .containsExactly("export.create");
        assertThat(auditPermissionFor(rosterExport.id())).isEqualTo("exports.studentList");
        assertThat(auditPermissionFor(rawExport.id())).isEqualTo("exports.serviceRecords");
    }

    private java.util.List<String> auditActionsFor(String jobId) {
        return jdbcTemplate.queryForList(
                "select action from audit_log where resource_type = 'export' and resource_id = :id",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", jobId), String.class);
    }

    private String auditPermissionFor(String jobId) {
        return jdbcTemplate.queryForObject(
                "select permission_used from audit_log where resource_type = 'export' and resource_id = :id",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", jobId), String.class);
    }

    /**
     * The five pre-split aggregate keys, and the roster's old name, are no
     * longer accepted (`11-DataImportExportOptimization.md` §3.1): two
     * export types retire outright and the rest are re-cut per type in
     * later phases. Asserted here because "the set was narrowed" is only
     * true if asking for an old key actually fails.
     */
    @Test
    void preSplitExportKeysAreRejected() {
        for (String retired : java.util.List.of("participation", "completion", "serviceHours", "topProjects", "students")) {
            assertThatThrownBy(() -> exportService.create(new CreateExportRequest(retired), "usr_test_admin"))
                    .as("export type %s must no longer be accepted", retired)
                    .isInstanceOf(ApiException.class);
        }
    }

    /** The "serviceRecords" export reads the `service_record` table directly — a real approved record, not a historical-import credit (which deliberately never creates one; see ImportJobRunner's Javadoc). */
    private void createAnApprovedServiceRecord(StudentDetailDto student, int minutes) throws Exception {
        cn.timebank.servicerecord.application.dto.ServiceRecordRequests.CreateServiceRecordRequest request =
                new cn.timebank.servicerecord.application.dto.ServiceRecordRequests.CreateServiceRecordRequest(
                        "SELF", null, null, null, LocalDate.now().minusDays(1),
                        Instant.now().minus(minutes, java.time.temporal.ChronoUnit.MINUTES), Instant.now(),
                        "Somewhere", "cat_campus", "desc", "EXTERNAL_MANUAL", null, "Test Supervisor", "test@example.com",
                        java.util.List.of(readyFile()), false, null);
        var draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
        var submitted = serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-export-fixture-" + draft.id(), "zh-CN");
        reviewService.decide(submitted.id(),
                new cn.timebank.review.application.dto.ReviewRequests.DecideRequest("APPROVE", "GOOD", null, submitted.version()),
                adminAuth(), "idem-export-fixture-decide-" + submitted.id(), "zh-CN");
    }

    private static final byte[] VALID_JPEG = cn.timebank.testsupport.TestImages.validJpegBytes();

    private String readyFile() {
        var intent = fileService.createUploadIntent(
                new cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest("evidence.jpg", "image/jpeg", VALID_JPEG.length, "serviceRecordEvidence"),
                "usr_test_admin");
        fileService.receiveContent(intent.fileId(), "usr_test_admin", new java.io.ByteArrayInputStream(VALID_JPEG));
        fileService.complete(intent.fileId(), "usr_test_admin");
        return intent.fileId();
    }

    private JobDto createAndValidateImport(StudentDetailDto s1, StudentDetailDto s2) {
        byte[] bytes = csvBytes(s1, s2);
        JobDto job = importService.create("serviceRecordsHistorical",
                new MockMultipartFile("file", "ledger.csv", "text/csv", bytes), "usr_test_admin");
        importService.validate(job.id(), "usr_test_admin");
        awaitStatus(job.id(), "VALIDATED");
        return jobRepository.findById(job.id()).map(this::toDto).orElseThrow();
    }

    private byte[] csvBytes(StudentDetailDto s1, StudentDetailDto s2) {
        String csv = "studentNumber,serviceDate,durationMinutes,description\n"
                + s1.studentNumber() + ",2025-10-01,120,Valid row one\n"
                + s2.studentNumber() + ",2025-10-02,60,Valid row two\n"
                + "no_such_student,2025-10-01,60,Unknown student\n"
                + s1.studentNumber() + ",not-a-date,60,Bad date\n"
                + s1.studentNumber() + ",2025-10-03,-5,Bad duration\n";
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    private void awaitStatus(String jobId, String expectedStatus) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
        while (Instant.now().isBefore(deadline)) {
            String status = jobRepository.findById(jobId).orElseThrow().getStatus();
            if (status.equals(expectedStatus)) return;
            if (status.endsWith("FAILED")) {
                throw new AssertionError("Job " + jobId + " reached " + status + " instead of " + expectedStatus
                        + ": " + jobRepository.findById(jobId).orElseThrow().getErrorMessage());
            }
            sleep();
        }
        throw new AssertionError("Job " + jobId + " did not reach " + expectedStatus + " within timeout");
    }

    private void awaitTerminal(String jobId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(25));
        while (Instant.now().isBefore(deadline)) {
            String status = jobRepository.findById(jobId).orElseThrow().getStatus();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) return;
            sleep();
        }
        throw new AssertionError("Job " + jobId + " did not finish within timeout");
    }

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JobDto toDto(Job job) {
        return new JobDto(job.getId(), job.getKind().name(), job.getResourceType(), job.getStatus(), job.getCreatedBy(),
                job.getTotalRows(), job.getValidRows(), job.getProcessedRows(), job.getCheckpointOffset(),
                job.getErrorMessage(), job.getVersion(), job.getCreatedAt(), job.getUpdatedAt(), job.getFinishedAt());
    }

    private StudentDetailDto createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_ie_" + n, "Import Export Student " + n, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1));
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private Authentication adminAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Authentication studentAuth(String studentId) {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_" + studentId.substring(4), "usr_" + studentId.substring(4), "hash", "Test Student", true, false, "zh-CN",
                Set.of("student"), Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
