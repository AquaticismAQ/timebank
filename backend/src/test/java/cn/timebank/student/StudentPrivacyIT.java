package cn.timebank.student;

import cn.timebank.audit.infrastructure.AuditLogRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract §9's exit criterion, proven against real MySQL: list rows never
 * carry guardian/email fields, detail rows only carry them when the caller
 * is permitted, and that permitted access is itself audited.
 */
class StudentPrivacyIT extends AbstractIntegrationTest {

    @Autowired
    private StudentService studentService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private static final String ACTOR = "usr_test_admin";

    @Test
    void listResponsesNeverCarryGuardianOrEmailFields() {
        createStudent("stu_privacy_1");

        // StudentListItemDto has no email/guardianName/guardianContact accessors
        // at all — this is a compile-time guarantee, not a runtime check. The
        // live assertion here is that the list actually returns the row.
        Page<StudentListItemDto> page = studentService.list(PageRequest.of(0, 20), null, null, null, null, "zh-CN");
        assertThat(page.getContent()).anyMatch(s -> s.studentNumber().equals("stu_privacy_1"));
    }

    @Test
    void listFiltersByGradeAndSearchesByNameOrStudentNumber() {
        createStudent("stu_privacy_4");

        Page<StudentListItemDto> byGrade = studentService.list(PageRequest.of(0, 20), "grade_6", null, null, null, "zh-CN");
        assertThat(byGrade.getContent()).anyMatch(s -> s.studentNumber().equals("stu_privacy_4"));

        Page<StudentListItemDto> wrongGrade = studentService.list(PageRequest.of(0, 20), "grade_9", null, null, null, "zh-CN");
        assertThat(wrongGrade.getContent()).noneMatch(s -> s.studentNumber().equals("stu_privacy_4"));

        Page<StudentListItemDto> bySearch = studentService.list(PageRequest.of(0, 20), null, null, null, "privacy_4", "zh-CN");
        assertThat(bySearch.getContent()).anyMatch(s -> s.studentNumber().equals("stu_privacy_4"));

        Page<StudentListItemDto> noMatch = studentService.list(PageRequest.of(0, 20), null, null, null, "no-such-student", "zh-CN");
        assertThat(noMatch.getContent()).isEmpty();
    }

    @Test
    void detailHidesSensitiveFieldsWhenNotPermittedAndDoesNotAudit() {
        StudentDetailDto created = createStudent("stu_privacy_2");

        long auditCountBefore = auditLogRepository
                .findByResourceTypeAndResourceId("student", created.id(), PageRequest.of(0, 50))
                .getTotalElements();

        StudentDetailDto unprivileged = studentService.getDetail(created.id(), false, ACTOR, "zh-CN");
        assertThat(unprivileged.email()).isNull();
        assertThat(unprivileged.guardianName()).isNull();
        assertThat(unprivileged.guardianContact()).isNull();

        long auditCountAfter = auditLogRepository
                .findByResourceTypeAndResourceId("student", created.id(), PageRequest.of(0, 50))
                .getTotalElements();
        assertThat(auditCountAfter).isEqualTo(auditCountBefore);
    }

    @Test
    void detailExposesSensitiveFieldsWhenPermittedAndAuditsTheAccess() {
        StudentDetailDto created = createStudent("stu_privacy_3");

        long auditCountBefore = auditLogRepository
                .findByResourceTypeAndResourceId("student", created.id(), PageRequest.of(0, 50))
                .getTotalElements();

        StudentDetailDto privileged = studentService.getDetail(created.id(), true, ACTOR, "zh-CN");
        assertThat(privileged.email()).isEqualTo("stu3@example.com");
        assertThat(privileged.guardianName()).isEqualTo("Guardian Three");
        assertThat(privileged.guardianContact()).isEqualTo("13800000003");

        long auditCountAfter = auditLogRepository
                .findByResourceTypeAndResourceId("student", created.id(), PageRequest.of(0, 50))
                .getTotalElements();
        assertThat(auditCountAfter).isEqualTo(auditCountBefore + 1);
    }

    /**
     * `GET /students/directory` (`Docs/clarification/01-i3-partner-school-terminology.md`):
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
        assertThat(results).allSatisfy(r -> assertThat(r.gradeName()).isNotEmpty());

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
                studentNumber, displayName, null, "grade_6", "class_6A", "ay_2025_2026",
                2029, LocalDate.of(2025, 9, 1), null, null);
        return studentService.create(request, ACTOR, "zh-CN");
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private StudentDetailDto createStudent(String studentNumber) {
        int suffix = studentNumber.charAt(studentNumber.length() - 1) - '0';
        CreateStudentRequest request = new CreateStudentRequest(
                studentNumber, "Test Student " + suffix, "stu" + suffix + "@example.com",
                "grade_6", "class_6A", "ay_2025_2026", 2029, LocalDate.of(2025, 9, 1),
                "Guardian " + guardianNameSuffix(suffix), "1380000000" + suffix);
        return studentService.create(request, ACTOR, "zh-CN");
    }

    private String guardianNameSuffix(int n) {
        return switch (n) {
            case 1 -> "One";
            case 2 -> "Two";
            case 3 -> "Three";
            default -> "N";
        };
    }
}
