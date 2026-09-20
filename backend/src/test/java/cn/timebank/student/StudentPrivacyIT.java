package cn.timebank.student;

import cn.timebank.audit.infrastructure.AuditLogRepository;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.domain.TeacherScopeAssignment;
import cn.timebank.security.infrastructure.TeacherScopeAssignmentRepository;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentDirectoryEntryDto;
import cn.timebank.student.application.dto.StudentListItemDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract §9's exit criterion, proven against real MySQL. Guardian and
 * roster-email fields no longer exist anywhere in the student model — V26
 * dropped them under data minimization (`Docs/optimization/
 * 08-SecurityPrivacyHardening.md` §3.1) — so the "list rows never carry them"
 * half is now a compile-time guarantee rather than a runtime assertion. What
 * is still asserted here is that claiming sensitive access is audited. Also
 * covers §11 acceptance scenario 8's data-scope proof for the student resource
 * (issue 001: teacher grade scope was never enforced here).
 */
class StudentPrivacyIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private TeacherScopeAssignmentRepository scopeAssignmentRepository;

    private static final String ACTOR = "usr_test_admin";

    @Test
    void listResponsesNeverCarryGuardianOrEmailFields() {
        String number = createStudent("stu_privacy_1").studentNumber();

        // StudentListItemDto has no email/guardianName/guardianContact accessors
        // at all — this is a compile-time guarantee, not a runtime check. The
        // live assertion here is that the list actually returns the row.
        Page<StudentListItemDto> page = studentService.list(PageRequest.of(0, 20), adminAuth(), null, null, null, null, "zh-CN");
        assertThat(page.getContent()).anyMatch(s -> s.studentNumber().equals(number));
    }

    @Test
    void listFiltersByGradeAndSearchesByNameOrStudentNumber() {
        String number = createStudent("stu_privacy_4").studentNumber();
        Authentication auth = adminAuth();

        Page<StudentListItemDto> byGrade = studentService.list(PageRequest.of(0, 20), auth, "grade_6", null, null, null, "zh-CN");
        assertThat(byGrade.getContent()).anyMatch(s -> s.studentNumber().equals(number));

        Page<StudentListItemDto> wrongGrade = studentService.list(PageRequest.of(0, 20), auth, "grade_9", null, null, null, "zh-CN");
        assertThat(wrongGrade.getContent()).noneMatch(s -> s.studentNumber().equals(number));

        // Searched in the case it was *typed*, not the case it is stored in —
        // the column's collation is case-insensitive, and an operator typing a
        // number off a printed slip should not have to match its casing.
        Page<StudentListItemDto> bySearch = studentService.list(PageRequest.of(0, 20), auth, null, null, null, "privacy_4", "zh-CN");
        assertThat(bySearch.getContent()).anyMatch(s -> s.studentNumber().equals(number));

        Page<StudentListItemDto> noMatch = studentService.list(PageRequest.of(0, 20), auth, null, null, null, "no-such-student", "zh-CN");
        assertThat(noMatch.getContent()).isEmpty();
    }

    /**
     * Why the two tests above compare against what {@code create} returned rather
     * than against the string they passed in.
     *
     * <p>{@code StudentServiceImpl.canonicalStudentNumber} stores one case
     * (`a0e9fa6`, 2026-09-16), so a number goes in as typed and comes back
     * uppercased. Both tests asserted on their own literal and went red the day
     * that landed. Asserting on the returned value fixes them without pinning the
     * casing rule in a second place — and this test pins it in the one place it
     * belongs, so the next person to meet a surprising uppercase student number
     * finds the rule asserted rather than assuming the tests are wrong and
     * reverting it.</p>
     */
    @Test
    void creationStoresTheStudentNumberInOneCanonicalCase() {
        StudentDetailDto created = createStudent("stu_privacy_5");

        assertThat(created.studentNumber()).isEqualTo("STU_PRIVACY_5");
    }

    /**
     * `APIContract.md` §11 acceptance scenario 8 described {@code teacher}
     * as grade-scoped — true when this test was written (issue 001), no
     * longer true after 07-RoleFinetune.md §2.1 made {@code teacher} a
     * {@code hasFullDataScope()} role like {@code admin}/{@code sysadmin}.
     * A {@code teacher_scope_assignment} row is now purely organizational
     * metadata; it doesn't narrow anything. This test used to assert the
     * opposite (a 404 and a narrowed list) — that described the
     * pre-07-RoleFinetune behavior §2.1 deliberately changed, not a
     * regression to guard against.
     */
    @Test
    // 07-RoleFinetune.md §2.10 (2026-08-20): reverted from §2.1's "any non-student sees
    // everything" back to grade-scoped visibility for teacher — see hasSchoolWideVisibility().
    void scopedTeacherSeesOnlyInScopeStudents() {
        StudentDetailDto inScopeStudent = createStudent("stu_scope_6"); // grade_6/class_6A
        StudentDetailDto outOfScopeStudent = createStudentInGrade9("stu_scope_9");

        Authentication teacherAuth = teacherScopedToGrade6();

        assertThat(studentService.getDetail(inScopeStudent.id(), teacherAuth, "zh-CN").id())
                .isEqualTo(inScopeStudent.id());
        assertThatThrownBy(() -> studentService.getDetail(outOfScopeStudent.id(), teacherAuth, "zh-CN"))
                .isInstanceOf(ApiException.class);

        Page<StudentListItemDto> list = studentService.list(PageRequest.of(0, 20), teacherAuth, null, null, null, null, "zh-CN");
        assertThat(list.getContent()).extracting(StudentListItemDto::id)
                .contains(inScopeStudent.id())
                .doesNotContain(outOfScopeStudent.id());
    }

    private Authentication adminAuth() {
        AuthenticatedUserView view = new AuthenticatedUserView(
                "usr_test_admin", "usr_test_admin", "hash", "Test Admin", true, false, "zh-CN",
                Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private Authentication teacherScopedToGrade6() {
        var teacher = userService.create(new CreateUserRequest(
                "scope_teacher_" + OpaqueIds.generate("u"), "Scope Teacher", null,
                "teacher", "zh-CN"), "usr_test_admin");
        scopeAssignmentRepository.save(new TeacherScopeAssignment(OpaqueIds.generate("scope"), teacher.id(),
                "students", TeacherScopeAssignment.ScopeType.GRADE, "grade_6", Instant.now()));
        AuthenticatedUserView view = new AuthenticatedUserView(
                teacher.id(), teacher.id(), "hash", "Scope Teacher", true, false, "zh-CN",
                Set.of("teacher"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private StudentDetailDto createStudentInGrade9(String studentNumber) {
        CreateStudentRequest request = new CreateStudentRequest(
                studentNumber, "Test Student 9", "grade_9", "class_9A", "ay_2025_2026",
                2026, LocalDate.of(2025, 9, 1));
        return studentService.create(request, ACTOR, "zh-CN");
    }

    /**
     * `GET /students/{id}` writes **no** audit row. It used to: an
     * {@code includeSensitiveFields} flag gated {@code email}/{@code guardianName}/
     * {@code guardianContact} and recorded {@code student.viewSensitive} whenever
     * they were shown. V26 dropped those columns, and issue 085 removed the flag
     * and the event — a plain read of non-sensitive fields is not an auditable
     * event, and on the original server that one action had grown to half of
     * every audit row, mostly students reading their own profile.
     *
     * This asserts the absence, because the failure mode being guarded against is
     * a well-meaning re-introduction of read auditing.
     */
    @Test
    void readingAStudentWritesNoAuditRow() {
        StudentDetailDto created = createStudent("stu_privacy_2");

        long before = auditLogRepository
                .findByResourceTypeAndResourceId("student", created.id(), PageRequest.of(0, 50))
                .getTotalElements();

        studentService.getDetail(created.id(), "zh-CN");

        long after = auditLogRepository
                .findByResourceTypeAndResourceId("student", created.id(), PageRequest.of(0, 50))
                .getTotalElements();
        assertThat(after).isEqualTo(before);
    }

    /**
     * `GET /students/directory` (`Docs/optimization/01-i3-partner-school-terminology.md`):
     * a co-organizer picker's search — deliberately narrower than the list
     * above (no studentNumber/status/graduationYear/version), capped, and
     * blank on an empty search rather than returning the whole roster.
     */
    @Test
    void directorySearchFindsByNameWithinACapAndIsBlankOnEmptySearch() {
        String marker = "Directory" + SEQ.incrementAndGet();
        createNumberedStudent("stu_dir_a_" + SEQ.get(), marker + " Alpha");
        createNumberedStudent("stu_dir_b_" + SEQ.incrementAndGet(), marker + " Beta");

        List<StudentDirectoryEntryDto> results = studentService.searchDirectory(marker, "zh-CN");
        assertThat(results).extracting(StudentDirectoryEntryDto::displayName)
                .contains(marker + " Alpha", marker + " Beta");
        // StudentDirectoryEntryDto has no studentNumber/status/graduationYear/
        // version accessor at all — a compile-time guarantee, same shape as
        // the list-response test above.
        assertThat(results).allSatisfy(r -> assertThat(r.gradeLevel()).isPositive());

        assertThat(studentService.searchDirectory(null, "zh-CN")).isEmpty();
        assertThat(studentService.searchDirectory("", "zh-CN")).isEmpty();
        assertThat(studentService.searchDirectory("no-such-student-name-anywhere", "zh-CN")).isEmpty();
    }

    @Test
    void directorySearchIsCappedAtTwenty() {
        String marker = "Capped" + SEQ.incrementAndGet();
        for (int i = 0; i < 25; i++) {
            createNumberedStudent("stu_cap_" + SEQ.incrementAndGet(), marker + " Student " + i);
        }

        List<StudentDirectoryEntryDto> results = studentService.searchDirectory(marker, "zh-CN");
        assertThat(results).hasSizeLessThanOrEqualTo(20);
    }

    private StudentDetailDto createNumberedStudent(String studentNumber, String displayName) {
        CreateStudentRequest request = new CreateStudentRequest(
                studentNumber, displayName, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1));
        return studentService.create(request, ACTOR, "zh-CN");
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private StudentDetailDto createStudent(String studentNumber) {
        int suffix = studentNumber.charAt(studentNumber.length() - 1) - '0';
        CreateStudentRequest request = new CreateStudentRequest(
                studentNumber, "Test Student " + suffix,
                "grade_6", "class_6A", "ay_2025_2026", 2029, LocalDate.of(2025, 9, 1));
        return studentService.create(request, ACTOR, "zh-CN");
    }
}
