package cn.timebank.servicerecord;

import cn.timebank.common.error.ApiException;
import cn.timebank.file.application.FileService;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.CreateServiceRecordRequest;
import cn.timebank.servicerecord.application.dto.ServiceRecordRequests.UpdateServiceRecordRequest;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B3.3's exit criterion — "each rule individually tested with its own
 * error code" — plus B3.2's optimistic-concurrency exit criterion
 * ("concurrent edits to one record return 409 with the current version").
 */
class ServiceRecordSubmissionIT extends AbstractIntegrationTest {

    private static final byte[] VALID_JPEG = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02, 0x03, 0x04,
            0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10};

    @Autowired
    private StudentService studentService;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private FileService fileService;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** A real, READY evidence file — submission validation now checks status, not just presence (B3.5). */
    private String readyFile() {
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("evidence.jpg", "image/jpeg", VALID_JPEG.length, "serviceRecordEvidence"),
                "usr_test_admin");
        fileService.receiveContent(intent.fileId(), "usr_test_admin", new ByteArrayInputStream(VALID_JPEG));
        fileService.complete(intent.fileId(), "usr_test_admin");
        return intent.fileId();
    }

    /** A file whose content has arrived but hasn't been completed yet (still {@code PROCESSING}). */
    private String processingFile() {
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("evidence.jpg", "image/jpeg", VALID_JPEG.length, "serviceRecordEvidence"),
                "usr_test_admin");
        fileService.receiveContent(intent.fileId(), "usr_test_admin", new ByteArrayInputStream(VALID_JPEG));
        return intent.fileId();
    }

    /** A file that failed the magic-byte check and is {@code REJECTED}. */
    private String rejectedFile() {
        byte[] notAJpeg = "not a jpeg".getBytes();
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("fake.jpg", "image/jpeg", notAJpeg.length, "serviceRecordEvidence"),
                "usr_test_admin");
        fileService.receiveContent(intent.fileId(), "usr_test_admin", new ByteArrayInputStream(notAJpeg));
        fileService.complete(intent.fileId(), "usr_test_admin");
        return intent.fileId();
    }

    @Test
    void aFileStillProcessingCannotBeAttachedToASubmission() {
        StudentDetailDto student = createStudent();
        ServiceRecordDto draft = createDraft(student, List.of(processingFile()), false);

        assertThatThrownBy(() -> serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-processing", "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("evidenceFileIds") && fe.code().equals("EVIDENCE_NOT_READY")));
    }

    @Test
    void aRejectedFileCannotBeAttachedToASubmission() {
        StudentDetailDto student = createStudent();
        ServiceRecordDto draft = createDraft(student, List.of(rejectedFile()), false);

        assertThatThrownBy(() -> serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-rejected", "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("evidenceFileIds") && fe.code().equals("EVIDENCE_NOT_READY")));
    }

    @Test
    void missingEvidenceIsRejectedWithItsOwnFieldError() {
        StudentDetailDto student = createStudent();
        ServiceRecordDto draft = createDraft(student, List.of(), false);

        assertThatThrownBy(() -> serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-1", "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("evidenceFileIds")));
    }

    @Test
    void aFutureServiceDateIsRejected() {
        StudentDetailDto student = createStudent();
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Instant end = start.plusSeconds(3600);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().plusDays(2), start, end,
                "Somewhere", "cat_campus", "desc", null, null, List.of(readyFile()), false);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");

        assertThatThrownBy(() -> serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-2", "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("serviceDate")));
    }

    @Test
    void anExternalRecordRequiresOrganizationAndSupervisor() {
        StudentDetailDto student = createStudent();
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "EXTERNAL", null, null, null, LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", null, null, List.of(readyFile()), false);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");

        assertThatThrownBy(() -> serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-3", "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .extracting(fe -> fe.field())
                        .contains("organizationName", "supervisorName"));
    }

    @Test
    void overlappingRecordsAreFlaggedAsAnomalyNotBlocked() {
        StudentDetailDto student = createStudent();
        Instant end1 = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant start1 = end1.minusSeconds(7200);
        ServiceRecordDto first = createDraft(student, List.of(readyFile()), false, LocalDate.now().minusDays(2), start1, end1);
        serviceRecordService.submit(first.id(), studentAuth(student.id()), "idem-overlap-1", "zh-CN");

        // Overlaps the first record's window.
        Instant start2 = start1.plusSeconds(1800);
        Instant end2 = end1.plusSeconds(1800);
        ServiceRecordDto second = createDraft(student, List.of(readyFile()), false, LocalDate.now().minusDays(2), start2, end2);
        ServiceRecordDto submittedSecond = serviceRecordService.submit(second.id(), studentAuth(student.id()), "idem-overlap-2", "zh-CN");

        assertThat(submittedSecond.hasAnomaly()).isTrue();
        assertThat(submittedSecond.anomalyNote()).containsIgnoringCase("overlaps");
    }

    @Test
    void travelTimeDisclosureFlagsAnomalyButDoesNotBlockSubmission() {
        StudentDetailDto student = createStudent();
        ServiceRecordDto submitted = createDraft(student, List.of(readyFile()), true);
        ServiceRecordDto result = serviceRecordService.submit(submitted.id(), studentAuth(student.id()), "idem-travel", "zh-CN");

        assertThat(result.status()).isEqualTo("SUBMITTED");
        assertThat(result.hasAnomaly()).isTrue();
        assertThat(result.anomalyNote()).containsIgnoringCase("travel");
    }

    @Test
    void concurrentEditsToOneRecordReturnConflictNotSilentOverwrite() {
        StudentDetailDto student = createStudent();
        ServiceRecordDto draft = createDraft(student, List.of("file_1"), false);

        // (projectId, organizationId, organizationName, serviceDate, startAt, endAt, location,
        //  serviceCategoryId, description, supervisorName, supervisorContact, evidenceFileIds,
        //  includesTravelTime, version)
        UpdateServiceRecordRequest staleUpdate = new UpdateServiceRecordRequest(
                null, null, null, null, null, null, null, null, "Updated description", null, null, null, null,
                draft.version());
        serviceRecordService.updateDraft(draft.id(), staleUpdate, studentAuth(student.id()), "zh-CN");

        // Reusing the now-stale `draft.version()` a second time must conflict.
        assertThatThrownBy(() -> serviceRecordService.updateDraft(draft.id(), staleUpdate, studentAuth(student.id()), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("version");
    }

    private ServiceRecordDto createDraft(StudentDetailDto student, List<String> evidenceFileIds, boolean includesTravelTime) {
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        return createDraft(student, evidenceFileIds, includesTravelTime, LocalDate.now().minusDays(1), start, end);
    }

    private ServiceRecordDto createDraft(StudentDetailDto student, List<String> evidenceFileIds, boolean includesTravelTime,
                                          LocalDate serviceDate, Instant start, Instant end) {
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, serviceDate, start, end,
                "Somewhere", "cat_campus", "desc", null, null, evidenceFileIds, includesTravelTime);
        return serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
    }

    private StudentDetailDto createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_submit_" + n, "Submit Student " + n, null, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1), null, null);
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private Authentication studentAuth(String studentId) {
        // Short, well within VARCHAR(32) — see ApprovalTransactionIT.studentAuth's note.
        String userId = "usr_" + studentId.substring(4);
        cn.timebank.iam.application.dto.AuthenticatedUserView view = new cn.timebank.iam.application.dto.AuthenticatedUserView(
                userId, userId, "hash", "Test Student", true, false, "zh-CN",
                Set.of("student"), Set.of(), studentId, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
