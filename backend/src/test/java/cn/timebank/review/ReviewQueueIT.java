package cn.timebank.review;

import cn.timebank.file.application.FileService;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.review.application.ReviewService;
import cn.timebank.review.application.dto.ReviewQueueItemDto;
import cn.timebank.review.application.dto.ReviewQueueSummaryDto;
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
import org.springframework.data.domain.PageRequest;
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
 * B3.6's exit criteria: the queue is a single hand-written projection (no
 * per-row entity-graph queries) that surfaces submitted/under-review
 * records with denormalized names and a computed anomaly/priority — proven
 * against a seeded, real dataset rather than reasoned about.
 */
class ReviewQueueIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private FileService fileService;

    private static final byte[] VALID_JPEG = cn.timebank.testsupport.TestImages.validJpegBytes();

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String readyFile() {
        UploadIntentDto intent = fileService.createUploadIntent(
                new CreateUploadIntentRequest("evidence.jpg", "image/jpeg", VALID_JPEG.length, "serviceRecordEvidence"),
                "usr_test_admin");
        fileService.receiveContent(intent.fileId(), "usr_test_admin", new ByteArrayInputStream(VALID_JPEG));
        fileService.complete(intent.fileId(), "usr_test_admin");
        return intent.fileId();
    }

    @Test
    void submittedRecordsAppearInTheQueueWithDenormalizedNamesAndComputedFields() {
        StudentDetailDto student = createStudent();
        ServiceRecordDto submitted = createAndSubmitRecord(student);

        var page = reviewService.queue(PageRequest.of(0, 20), null, null, adminAuth(), "zh-CN");

        assertThat(page.getContent()).extracting(ReviewQueueItemDto::id).contains(submitted.id());
        ReviewQueueItemDto item = page.getContent().stream().filter(i -> i.id().equals(submitted.id())).findFirst().orElseThrow();
        assertThat(item.studentName()).isEqualTo(student.displayName());
        assertThat(item.serviceCategoryName()).isNotBlank();
        assertThat(item.status()).isEqualTo("SUBMITTED");
        assertThat(item.priority()).isIn("low", "medium", "high");
    }

    @Test
    void summaryCountsReflectPendingRecords() {
        StudentDetailDto student = createStudent();
        createAndSubmitRecord(student);

        ReviewQueueSummaryDto before = reviewService.summary(adminAuth());
        StudentDetailDto student2 = createStudent();
        createAndSubmitRecord(student2);
        ReviewQueueSummaryDto after = reviewService.summary(adminAuth());

        assertThat(after.pendingCount()).isEqualTo(before.pendingCount() + 1);
    }

    @Test
    void draftRecordsDoNotAppearInTheQueue() {
        StudentDetailDto student = createStudent();
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(1),
                Instant.now().minus(1, ChronoUnit.DAYS).minusSeconds(3600), Instant.now().minus(1, ChronoUnit.DAYS),
                "Somewhere", "cat_campus", "desc", null, null, List.of(readyFile()), false);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");

        var page = reviewService.queue(PageRequest.of(0, 50), null, null, adminAuth(), "zh-CN");
        assertThat(page.getContent()).extracting(ReviewQueueItemDto::id).doesNotContain(draft.id());
    }

    @Test
    void queueFiltersByAnomalyAndSearchesStudentNameAndOrganization() {
        StudentDetailDto student = createStudent();
        ServiceRecordDto submitted = createAndSubmitRecord(student);

        var byAnomaly = reviewService.queue(PageRequest.of(0, 50), true, null, adminAuth(), "zh-CN");
        assertThat(byAnomaly.getContent()).extracting(ReviewQueueItemDto::id).doesNotContain(submitted.id());

        var notAnomaly = reviewService.queue(PageRequest.of(0, 50), false, null, adminAuth(), "zh-CN");
        assertThat(notAnomaly.getContent()).extracting(ReviewQueueItemDto::id).contains(submitted.id());

        var bySearch = reviewService.queue(PageRequest.of(0, 50), null, student.displayName(), adminAuth(), "zh-CN");
        assertThat(bySearch.getContent()).extracting(ReviewQueueItemDto::id).contains(submitted.id());
        assertThat(bySearch.getTotalElements()).isEqualTo(bySearch.getContent().size());

        var noMatch = reviewService.queue(PageRequest.of(0, 50), null, "no-such-person", adminAuth(), "zh-CN");
        assertThat(noMatch.getContent()).isEmpty();
    }

    private StudentDetailDto createStudent() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_queue_" + n, "Queue Student " + n, null, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1), null, null);
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private ServiceRecordDto createAndSubmitRecord(StudentDetailDto student) {
        Instant end = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(2), start, end,
                "Somewhere", "cat_campus", "desc", null, null, List.of(readyFile()), false);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");
        return serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-submit-" + draft.id(), "zh-CN");
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

    private Authentication adminAuth() {
        cn.timebank.iam.application.dto.AuthenticatedUserView view = new cn.timebank.iam.application.dto.AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
