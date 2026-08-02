package cn.timebank.student.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.StudentDetailDto;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** Contract §4.9. Progress and service-history sub-resources land in B3 (out of B2's scope). */
@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    private static final Set<String> SORTABLE = Set.of("id", "studentNumber", "displayName", "createdAt");

    private final StudentService studentService;
    private final RequestLanguageService requestLanguageService;

    public StudentController(StudentService studentService, RequestLanguageService requestLanguageService) {
        this.studentService = studentService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('students.read')")
    public ApiResponse<List<StudentListItemDto>> list(
            PageQuery query, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<StudentListItemDto> page = studentService.list(pageable, resolveLanguage(acceptLanguage, auth));
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('students.read') or #id == principal.studentId()")
    public ApiResponse<StudentDetailDto> get(
            @PathVariable String id, @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        CookieSessionUserDetails principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean sensitive = principal.hasPermission("students.manage") || id.equals(principal.studentId());
        return ApiResponse.of(studentService.getDetail(id, sensitive, principal.id(), resolveLanguage(acceptLanguage, auth)));
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
        return ApiResponse.of(studentService.update(id, request, principal.id(), resolveLanguage(acceptLanguage, auth)));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('students.manage')")
    public ApiResponse<Void> activate(@PathVariable String id, Authentication auth) {
        studentService.activate(id, principalId(auth));
        return ApiResponse.of(null);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('students.manage')")
    public ApiResponse<Void> deactivate(@PathVariable String id, Authentication auth) {
        studentService.deactivate(id, principalId(auth));
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
