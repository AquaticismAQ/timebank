package cn.timebank.reporting.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.reporting.application.ReportService;
import cn.timebank.reporting.application.dto.CompletionReportDto;
import cn.timebank.reporting.application.dto.ParticipationReportDto;
import cn.timebank.reporting.application.dto.ReviewEfficiencyReportDto;
import cn.timebank.reporting.application.dto.ServiceHoursReportDto;
import cn.timebank.reporting.application.dto.TopProjectsReportDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract §4.13, all five endpoints `reports.read`-gated.
 *
 * <p>Every endpoint takes an optional {@code ?academicYearId=} (issue 093
 * §1). Omitted or blank means the current academic year; an id no academic
 * year has is a 404 rather than a quiet fall back — see
 * {@code ReportAcademicYearResolver}.</p>
 */
@RestController
public class ReportController {

    private final ReportService reportService;
    private final RequestLanguageService requestLanguageService;

    public ReportController(ReportService reportService, RequestLanguageService requestLanguageService) {
        this.reportService = reportService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping("/api/v1/reports/participation")
    @PreAuthorize("hasAuthority('reports.read')")
    public ApiResponse<ParticipationReportDto> participation(
            @RequestParam(value = "academicYearId", required = false) String academicYearId,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(reportService.participation(resolveLanguage(acceptLanguage, auth), academicYearId));
    }

    @GetMapping("/api/v1/reports/completion")
    @PreAuthorize("hasAuthority('reports.read')")
    public ApiResponse<CompletionReportDto> completion(
            @RequestParam(value = "academicYearId", required = false) String academicYearId,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        return ApiResponse.of(reportService.completion(resolveLanguage(acceptLanguage, auth), academicYearId));
    }

    @GetMapping("/api/v1/reports/service-hours")
    @PreAuthorize("hasAuthority('reports.read')")
    public ApiResponse<ServiceHoursReportDto> serviceHours(
            @RequestParam(value = "academicYearId", required = false) String academicYearId, Authentication auth) {
        return ApiResponse.of(reportService.serviceHours(academicYearId, auth));
    }

    @GetMapping("/api/v1/reports/review-efficiency")
    @PreAuthorize("hasAuthority('reports.read')")
    public ApiResponse<ReviewEfficiencyReportDto> reviewEfficiency(
            @RequestParam(value = "academicYearId", required = false) String academicYearId) {
        return ApiResponse.of(reportService.reviewEfficiency(academicYearId));
    }

    @GetMapping("/api/v1/reports/top-projects")
    @PreAuthorize("hasAuthority('reports.read')")
    public ApiResponse<TopProjectsReportDto> topProjects(
            @RequestParam(value = "academicYearId", required = false) String academicYearId, Authentication auth) {
        return ApiResponse.of(reportService.topProjects(academicYearId, auth));
    }

    private String resolveLanguage(String acceptLanguage, Authentication auth) {
        String preferred = auth != null && auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return requestLanguageService.resolve(acceptLanguage, preferred);
    }
}
