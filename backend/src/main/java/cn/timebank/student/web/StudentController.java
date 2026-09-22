package cn.timebank.student.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentReminderService;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
import cn.timebank.student.application.dto.StudentDirectoryEntryDto;
import cn.timebank.student.application.dto.StudentListItemDto;
import cn.timebank.student.application.dto.StudentRequests.CreateStudentRequest;
import cn.timebank.student.application.dto.StudentRequests.UpdateStudentRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Contract §4.9. Progress and service-history sub-resources land in B3 (out of B2's scope). */
@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    private static final Set<String> SORTABLE = Set.of("id", "studentNumber", "displayName", "createdAt");

    private final StudentService studentService;
    private final StudentReminderService studentReminderService;
    private final RequestLanguageService requestLanguageService;

    public StudentController(StudentService studentService, StudentReminderService studentReminderService,
                             RequestLanguageService requestLanguageService) {
        this.studentService = studentService;
        this.studentReminderService = studentReminderService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('students.read')")
    public ApiResponse<List<StudentListItemDto>> list(
            PageQuery query, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<StudentListItemDto> page = studentService.list(pageable, auth, query.filter("gradeId"), query.filter("classId"),
                query.filter("status"), query.filter("progressStatus"), query.search(),
                resolveLanguage(acceptLanguage, auth));
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    /**
     * Contract §4.9: a small, capped, name-matched search for a co-organizer
     * picker — {@code directory.read} is what {@code role_student} actually
     * holds (students have no other way to look up another student's id).
     * Registered as a literal {@code /directory} segment ahead of
     * {@code /{id}} in this class; Spring's exact-match precedence means
     * ordering here doesn't matter for routing, but the exact-vs-pattern
     * pairing is the same one already proven for {@code /notifications/
     * unread-count} next to {@code /notifications/{id}}.
     */
    @GetMapping("/directory")
    @PreAuthorize("hasAuthority('students.read') or hasAuthority('directory.read')")
    public ApiResponse<List<StudentDirectoryEntryDto>> directory(
            @RequestParam(required = false) String search,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(studentService.searchDirectory(search, resolveLanguage(acceptLanguage, auth)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('students.read') or #id == principal.studentId()")
    public ApiResponse<StudentDetailDto> get(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        return ApiResponse.of(studentService.getDetail(id, auth, resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('students.manage')")
    public ApiResponse<StudentDetailDto> create(
            @Valid @RequestBody CreateStudentRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        CookieSessionUserDetails principal = (CookieSessionUserDetails) auth.getPrincipal();
        return ApiResponse.of(studentService.create(request, principal.id(), resolveLanguage(acceptLanguage, auth)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('students.manage')")
    public ApiResponse<StudentDetailDto> update(
            @PathVariable String id, @Valid @RequestBody UpdateStudentRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        CookieSessionUserDetails principal = (CookieSessionUserDetails) auth.getPrincipal();
        return ApiResponse.of(studentService.update(id, request, auth, principal.id(), resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('students.manage')")
    public ApiResponse<Void> activate(@PathVariable String id, Authentication auth) {
        studentService.activate(id, auth, principalId(auth));
        return ApiResponse.of(null);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('students.manage')")
    public ApiResponse<Void> deactivate(@PathVariable String id, Authentication auth) {
        studentService.deactivate(id, auth, principalId(auth));
        return ApiResponse.of(null);
    }

    /**
     * Issue 155: Student Management's <i>Remind</i> action, which until now
     * showed a toast and sent nothing.
     *
     * <p>Gated on {@code students.read}, not {@code students.manage}: a teacher
     * who can see a student's progress is exactly the person who should be able
     * to nudge them about it, and the data scope inside decides which students
     * that is. Sending a message is not editing a record.</p>
     */
    @PostMapping("/{id}/reminders")
    @PreAuthorize("hasAuthority('students.read')")
    public ApiResponse<Void> remind(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        studentReminderService.remind(id, auth, principalId(auth), resolveLanguage(acceptLanguage, auth));
        return ApiResponse.of(null);
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
