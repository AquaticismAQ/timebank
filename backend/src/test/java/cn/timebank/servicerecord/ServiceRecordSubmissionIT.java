package cn.timebank.servicerecord;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.file.application.FileService;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.domain.TeacherScopeAssignment;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    private static final byte[] VALID_JPEG = cn.timebank.testsupport.TestImages.validJpegBytes();

    @Autowired
    private StudentService studentService;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private FileService fileService;
    @Autowired
    private UserService userService;
    @Autowired
    private TeacherScopeAssignmentRepository scopeAssignmentRepository;

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
                "Somewhere", "cat_campus", "desc", null, null, null, null, List.of(readyFile()), false, null);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");

        assertThatThrownBy(() -> serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-2", "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .anyMatch(fe -> fe.field().equals("serviceDate")));
    }

    /** issue 03 §3.2/§10 Q1 — supersedes the old EXTERNAL-source "organizationName + supervisorName required" test; EXTERNAL is historical-only now, SELF requires a resolved supervisor instead. */
    @Test
    void aSelfInitiatedRecordRequiresASupervisor() {
        StudentDetailDto student = createStudent();
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", null, null, null, null, List.of(readyFile()), false, null);
        ServiceRecordDto draft = serviceRecordService.createDraft(request, student.id(), student.id(), "zh-CN");

        assertThatThrownBy(() -> serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-3", "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fieldErrors())
                        .extracting(fe -> fe.field())
                        .contains("supervisorKind"));
    }

    /**
     * Issue 069: an overlap is a hard block at submit, not an anomaly flag for
     * the reviewer to catch later. The blocked record must stay submittable-
     * later — i.e. still DRAFT — so the student's "keep as draft" choice in the
     * UI has something real to keep.
     */
    @Test
    void overlappingRecordsAreBlockedAtSubmitAndLeftAsDraft() {
        StudentDetailDto student = createStudent();
        Instant end1 = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant start1 = end1.minusSeconds(7200);
        ServiceRecordDto first = createDraft(student, List.of(readyFile()), false, LocalDate.now().minusDays(2), start1, end1);
        serviceRecordService.submit(first.id(), studentAuth(student.id()), "idem-overlap-1", "zh-CN");

        // Overlaps the first record's window.
        Instant start2 = start1.plusSeconds(1800);
        Instant end2 = end1.plusSeconds(1800);
        ServiceRecordDto second = createDraft(student, List.of(readyFile()), false, LocalDate.now().minusDays(2), start2, end2);

        assertThatThrownBy(() -> serviceRecordService.submit(second.id(), studentAuth(student.id()), "idem-overlap-2", "zh-CN"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).fieldErrors())
                            .extracting(fe -> fe.code())
                            .contains("OVERLAPPING_RECORD", "OVERLAPPING_RECORD_ID");
                    // The id entry must name the record actually clashed with, so the
                    // student can be shown which one rather than just "some conflict".
                    assertThat(((ApiException) e).fieldErrors())
                            .filteredOn(fe -> "OVERLAPPING_RECORD_ID".equals(fe.code()))
                            .extracting(fe -> fe.message())
                            .containsExactly(first.id());
                });

        assertThat(serviceRecordService.getDetail(second.id(), studentAuth(student.id()), "zh-CN").status())
                .isEqualTo("DRAFT");
    }

    /** The overlap check deliberately ignores DRAFT records, so parking one can never block a later submit. */
    @Test
    void anOverlappingDraftDoesNotBlockSubmission() {
        StudentDetailDto student = createStudent();
        Instant end1 = Instant.now().minus(3, ChronoUnit.DAYS);
        Instant start1 = end1.minusSeconds(7200);
        createDraft(student, List.of(readyFile()), false, LocalDate.now().minusDays(3), start1, end1);

        ServiceRecordDto other = createDraft(student, List.of(readyFile()), false, LocalDate.now().minusDays(3),
                start1.plusSeconds(1800), end1.plusSeconds(1800));

        assertThat(serviceRecordService.submit(other.id(), studentAuth(student.id()), "idem-overlap-3", "zh-CN").status())
                .isEqualTo("SUBMITTED");
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
        //  serviceCategoryId, description, supervisorKind, supervisorSubjectId, supervisorName,
        //  supervisorContact, evidenceFileIds, includesTravelTime, travelTimeReason, version)
        UpdateServiceRecordRequest staleUpdate = new UpdateServiceRecordRequest(
                null, null, null, null, null, null, null, null, "Updated description", null, null, null, null, null,
                null, null, draft.version());
        serviceRecordService.updateDraft(draft.id(), staleUpdate, studentAuth(student.id()), "zh-CN");

        // Reusing the now-stale `draft.version()` a second time must conflict.
        assertThatThrownBy(() -> serviceRecordService.updateDraft(draft.id(), staleUpdate, studentAuth(student.id()), "zh-CN"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("version");
    }

    /** issue 059 — an edited draft has service_record_revision rows FK'd to it; deleting it used to 500 with a raw FK constraint violation. */
    @Test
    void deletingADraftThatWasPreviouslyEditedSucceeds() {
        StudentDetailDto student = createStudent();
        ServiceRecordDto draft = createDraft(student, List.of("file_1"), false);

        UpdateServiceRecordRequest edit = new UpdateServiceRecordRequest(
                null, null, null, null, null, null, null, null, "Edited before delete", null, null, null, null, null,
                null, null, draft.version());
        serviceRecordService.updateDraft(draft.id(), edit, studentAuth(student.id()), "zh-CN");

        serviceRecordService.deleteDraft(draft.id(), studentAuth(student.id()));

        assertThatThrownBy(() -> serviceRecordService.getDetail(draft.id(), studentAuth(student.id()), "zh-CN"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void listFiltersByStatusAndSearchesByOrganizationName() {
        StudentDetailDto student = createStudent();

        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        CreateServiceRecordRequest requestA = new CreateServiceRecordRequest(
                "SELF", null, null, "Sunrise Shelter", LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", "EXTERNAL_MANUAL", null, "Test Supervisor", "test@example.com",
                List.of(readyFile()), false, null);
        ServiceRecordDto recordA = serviceRecordService.createDraft(requestA, student.id(), student.id(), "zh-CN");
        serviceRecordService.submit(recordA.id(), studentAuth(student.id()), "idem-list-a", "zh-CN");

        CreateServiceRecordRequest requestB = new CreateServiceRecordRequest(
                "SELF", null, null, "Riverside Library", LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", null, null, null, null, List.of(readyFile()), false, null);
        // recordB is left in DRAFT status (not submitted).
        ServiceRecordDto recordB = serviceRecordService.createDraft(requestB, student.id(), student.id(), "zh-CN");

        Pageable pageable = PageRequest.of(0, 20);
        Authentication auth = studentAuth(student.id());

        // A status filter narrows results to only the SUBMITTED record.
        Page<ServiceRecordDto> submittedOnly = serviceRecordService.list(pageable, auth, "SUBMITTED", null, null, null, null, "zh-CN");
        assertThat(submittedOnly.getContent()).extracting(ServiceRecordDto::id).containsExactly(recordA.id());

        // A search substring matches on organizationName.
        Page<ServiceRecordDto> searchMatch = serviceRecordService.list(pageable, auth, null, null, null, null, "sunrise", "zh-CN");
        assertThat(searchMatch.getContent()).extracting(ServiceRecordDto::id).containsExactly(recordA.id());

        // An unmatched search returns an empty page.
        Page<ServiceRecordDto> searchNoMatch = serviceRecordService.list(pageable, auth, null, null, null, null, "no-such-organization", "zh-CN");
        assertThat(searchNoMatch.getContent()).isEmpty();

        // A studentId filter (the admin "view this student's records" path) narrows to just that student's
        // records, even though a second student also has records in the same window. Both students are
        // grade_6 (createStudent()), so a grade_6-scoped teacher can see either — same scope mechanism
        // AccountService.list's studentId filter already relies on.
        StudentDetailDto otherStudent = createStudent();
        CreateServiceRecordRequest requestC = new CreateServiceRecordRequest(
                "SELF", null, null, "Harbor Museum", LocalDate.now().minusDays(1), start, end,
                "Somewhere", "cat_campus", "desc", null, null, null, null, List.of(readyFile()), false, null);
        ServiceRecordDto recordC = serviceRecordService.createDraft(requestC, otherStudent.id(), otherStudent.id(), "zh-CN");

        Authentication scopedTeacher = teacherScopedToGrade6();
        Page<ServiceRecordDto> byStudentA = serviceRecordService.list(pageable, scopedTeacher, null, null, null, student.id(), null, "zh-CN");
        assertThat(byStudentA.getContent()).extracting(ServiceRecordDto::id).containsExactlyInAnyOrder(recordA.id(), recordB.id());

        Page<ServiceRecordDto> byStudentC = serviceRecordService.list(pageable, scopedTeacher, null, null, null, otherStudent.id(), null, "zh-CN");
        assertThat(byStudentC.getContent()).extracting(ServiceRecordDto::id).containsExactly(recordC.id());

        assertThat(recordB.status()).isEqualTo("DRAFT");
    }

    /**
     * `APIContract.md` §11 acceptance scenario 8 described {@code teacher}
     * as grade-scoped — true when this test was written, no longer true
     * after 07-RoleFinetune.md §2.1 made {@code teacher} a {@code
     * hasFullDataScope()} role like {@code admin}/{@code sysadmin}. A
     * {@code teacher_scope_assignment} row is now purely organizational
     * metadata; it doesn't narrow anything. This test used to assert the
     * opposite (a 404 and a narrowed list) — that described the
     * pre-07-RoleFinetune behavior §2.1 deliberately changed, not a
     * regression to guard against. Same for the mirrored proofs on Cases,
     * Proposals, Students, and Accounts.
     */
    @Test
    // 07-RoleFinetune.md §2.10 (2026-08-20): reverted from §2.1's "any non-student sees
    // everything" back to grade-scoped visibility for teacher — see hasSchoolWideVisibility().
    void scopedTeacherSeesOnlyInScopeRecords() {
        StudentDetailDto inScopeStudent = createStudent(); // grade_6/class_6A
        StudentDetailDto outOfScopeStudent = createStudentInGrade9();
        ServiceRecordDto inScopeRecord = submitRecord(inScopeStudent);
        ServiceRecordDto outOfScopeRecord = submitRecord(outOfScopeStudent);

        Authentication teacherAuth = teacherScopedToGrade6();

        assertThat(serviceRecordService.getDetail(inScopeRecord.id(), teacherAuth, "zh-CN").id())
                .isEqualTo(inScopeRecord.id());
        assertThatThrownBy(() -> serviceRecordService.getDetail(outOfScopeRecord.id(), teacherAuth, "zh-CN"))
                .isInstanceOf(ApiException.class);

        Page<ServiceRecordDto> list = serviceRecordService.list(
                PageRequest.of(0, 20), teacherAuth, null, null, null, null, null, "zh-CN");
        assertThat(list.getContent()).extracting(ServiceRecordDto::id)
                .contains(inScopeRecord.id())
                .doesNotContain(outOfScopeRecord.id());
    }

    private ServiceRecordDto submitRecord(StudentDetailDto student) {
        ServiceRecordDto draft = createDraft(student, List.of(readyFile()), false);
        return serviceRecordService.submit(draft.id(), studentAuth(student.id()), "idem-scope-" + draft.id(), "zh-CN");
    }

    private Authentication teacherScopedToGrade6() {
        var teacher = userService.create(new CreateUserRequest(
                "scope_teacher_" + OpaqueIds.generate("u"), "InitialPass123!", "Scope Teacher", null,
                "teacher", "zh-CN"), "usr_test_admin");
        scopeAssignmentRepository.save(new TeacherScopeAssignment(OpaqueIds.generate("scope"), teacher.id(),
                "serviceRecords", TeacherScopeAssignment.ScopeType.GRADE, "grade_6", Instant.now()));
        cn.timebank.iam.application.dto.AuthenticatedUserView view = new cn.timebank.iam.application.dto.AuthenticatedUserView(
                teacher.id(), teacher.id(), "hash", "Scope Teacher", true, false, "zh-CN",
                Set.of("teacher"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private StudentDetailDto createStudentInGrade9() {
        int n = SEQ.incrementAndGet();
        CreateStudentRequest request = new CreateStudentRequest(
                "stu_submit9_" + n, "Submit Student " + n, null, "grade_9", "class_9A", "ay_2025_2026",
                2026, LocalDate.of(2025, 9, 1), null, null);
        return studentService.create(request, "usr_test_admin", "zh-CN");
    }

    private ServiceRecordDto createDraft(StudentDetailDto student, List<String> evidenceFileIds, boolean includesTravelTime) {
        Instant end = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant start = end.minusSeconds(3600);
        return createDraft(student, evidenceFileIds, includesTravelTime, LocalDate.now().minusDays(1), start, end);
    }

    private ServiceRecordDto createDraft(StudentDetailDto student, List<String> evidenceFileIds, boolean includesTravelTime,
                                          LocalDate serviceDate, Instant start, Instant end) {
        // EXTERNAL_MANUAL is the simplest resolvable supervisorKind for a test fixture
        // (no real user/student subject lookup needed) — most tests here exercise
        // evidence/date/anomaly rules, not supervisor resolution itself, and submission
        // now requires SOME supervisor to be set (issue 03 §3.2).
        CreateServiceRecordRequest request = new CreateServiceRecordRequest(
                "SELF", null, null, null, serviceDate, start, end,
                "Somewhere", "cat_campus", "desc", "EXTERNAL_MANUAL", null, "Test Supervisor", "test@example.com",
                evidenceFileIds, includesTravelTime, includesTravelTime ? "Travel reason" : null);
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
