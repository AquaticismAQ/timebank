package cn.timebank.student.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.dto.AcademicYearDto;
import cn.timebank.student.application.dto.ClassDto;
import cn.timebank.student.application.dto.GradeDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Contract §4.9's dictionary reads: `/academic-years`, `/grades`, `/classes` — any authenticated user. */
@RestController
public class AcademicStructureController {

    private final AcademicStructureService academicStructureService;
    private final RequestLanguageService requestLanguageService;

    public AcademicStructureController(AcademicStructureService academicStructureService,
                                        RequestLanguageService requestLanguageService) {
        this.academicStructureService = academicStructureService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping("/api/v1/academic-years")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<AcademicYearDto>> listAcademicYears() {
        return ApiResponse.of(academicStructureService.listAcademicYears());
    }

    @GetMapping("/api/v1/grades")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<GradeDto>> listGrades(
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(academicStructureService.listGrades(resolveLanguage(acceptLanguage, auth)));
    }

    @GetMapping("/api/v1/classes")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ClassDto>> listClasses(
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(academicStructureService.listClasses(resolveLanguage(acceptLanguage, auth)));
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }
}
