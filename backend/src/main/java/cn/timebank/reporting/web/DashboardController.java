package cn.timebank.reporting.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.reporting.application.DashboardService;
import cn.timebank.reporting.application.dto.DashboardAdminDto;
import cn.timebank.reporting.application.dto.DashboardStudentDto;
import cn.timebank.reporting.application.dto.DashboardTeacherDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Contract §4.13/§12.4. Owner-gated for the student's own dashboard (isAuthenticated(); the real check is "does this principal have a studentId" in the service — same 404-not-403 shape as `cases`); teacher/admin dashboards are permission-gated directly since there is no ownership question. */
@RestController
public class DashboardController {

    private final DashboardService dashboardService;
    private final RequestLanguageService requestLanguageService;

    public DashboardController(DashboardService dashboardService, RequestLanguageService requestLanguageService) {
        this.dashboardService = dashboardService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping("/api/v1/dashboards/student")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<DashboardStudentDto> student(
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(dashboardService.student(auth, resolveLanguage(acceptLanguage, auth)));
    }

    @GetMapping("/api/v1/dashboards/teacher")
    @PreAuthorize("hasAuthority('reviews.read')")
    public ApiResponse<DashboardTeacherDto> teacher(
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(dashboardService.teacher(auth, resolveLanguage(acceptLanguage, auth)));
    }

    @GetMapping("/api/v1/dashboards/admin")
    @PreAuthorize("hasAuthority('reports.read')")
    public ApiResponse<DashboardAdminDto> admin(
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(dashboardService.admin(auth, resolveLanguage(acceptLanguage, auth)));
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }
}
