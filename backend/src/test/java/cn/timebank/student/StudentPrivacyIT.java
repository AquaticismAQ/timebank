package cn.timebank.student;

import cn.timebank.audit.infrastructure.AuditLogRepository;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentListItemDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;

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
        Page<StudentListItemDto> page = studentService.list(PageRequest.of(0, 20), "zh-CN");
        assertThat(page.getContent()).anyMatch(s -> s.studentNumber().equals("stu_privacy_1"));
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
