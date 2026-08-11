package cn.timebank.reporting.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.account.application.dto.AccountDto;
import cn.timebank.common.cache.CacheConfig;
import cn.timebank.common.error.ApiException;
import cn.timebank.notification.application.NotificationService;
import cn.timebank.progress.application.ProgressService;
import cn.timebank.project.application.ProjectService;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.reporting.application.dto.AdminStatsDto;
import cn.timebank.reporting.application.dto.DashboardAdminDto;
import cn.timebank.reporting.application.dto.DashboardStudentDto;
import cn.timebank.reporting.application.dto.DashboardTeacherDto;
import cn.timebank.reporting.infrastructure.ReportQueryRepository;
import cn.timebank.review.application.ReviewService;
import cn.timebank.review.application.dto.ReviewQueueItemDto;
import cn.timebank.review.application.dto.ReviewQueueSummaryDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;
import cn.timebank.student.application.dto.AcademicYearDto;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

@Service
class DashboardServiceImpl implements DashboardService {

    private static final String SCOPE_RESOURCE_TYPE = "serviceRecords";

    private final ProgressService progressService;
    private final AccountService accountService;
    private final ServiceRecordService serviceRecordService;
    private final ProjectService projectService;
    private final NotificationService notificationService;
    private final ReviewService reviewService;
    private final ReportQueryRepository queryRepository;
    private final DataScopeResolver dataScopeResolver;
    private final ReportService reportService;
    private final CurrentAcademicYearResolver currentAcademicYearResolver;

    DashboardServiceImpl(ProgressService progressService, AccountService accountService,
                         ServiceRecordService serviceRecordService, ProjectService projectService,
                         NotificationService notificationService, ReviewService reviewService,
                         ReportQueryRepository queryRepository, DataScopeResolver dataScopeResolver,
                         ReportService reportService, CurrentAcademicYearResolver currentAcademicYearResolver) {
        this.progressService = progressService;
        this.accountService = accountService;
        this.serviceRecordService = serviceRecordService;
        this.projectService = projectService;
        this.notificationService = notificationService;
        this.reviewService = reviewService;
        this.queryRepository = queryRepository;
        this.dataScopeResolver = dataScopeResolver;
        this.reportService = reportService;
        this.currentAcademicYearResolver = currentAcademicYearResolver;
    }

    @Override
    @Cacheable(value = CacheConfig.DASHBOARDS, key = "'student:' + #auth.name")
    public DashboardStudentDto student(Authentication auth, String resolvedLanguage) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        String studentId = principal.studentId();
        if (studentId == null) {
            throw ApiException.forbidden("Only a student principal has a student dashboard.");
        }

        var progress = progressService.getProgress(studentId, auth);
        AccountDto account;
        try {
            account = accountService.getByStudentId(studentId, auth);
        } catch (ApiException e) {
            account = new AccountDto(null, studentId, 0, false, null, null, null, null);
        }

        List<ServiceRecordDto> recentRecords = serviceRecordService
                .list(PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt")), auth, null, null, null, null, resolvedLanguage)
                .getContent();
        List<ProjectDto> upcomingProjects = projectService
                .list(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "startAt")), null, null, null, auth, resolvedLanguage)
                .getContent().stream()
                .filter(p -> "REGISTRATION_OPEN".equals(p.status()))
                .limit(3)
                .toList();
        long unreadCount = notificationService.unreadCount(principal.id());

        return new DashboardStudentDto(Instant.now(), progress, account, recentRecords, upcomingProjects, unreadCount);
    }

    @Override
    @Cacheable(value = CacheConfig.DASHBOARDS, key = "'teacher:' + #auth.name")
    public DashboardTeacherDto teacher(Authentication auth, String resolvedLanguage) {
        ReviewQueueSummaryDto summary = reviewService.summary(auth);
        long scopedStudentCount = queryRepository.scopedActiveStudentCount(resolveScope(auth));
        // queue() already orders by submittedAt internally (B3.6's ReviewQueueRepository); no Sort needed here.
        List<ReviewQueueItemDto> topPending = reviewService.queue(PageRequest.of(0, 5), null, null, null, auth, resolvedLanguage).getContent();
        return new DashboardTeacherDto(Instant.now(), summary, scopedStudentCount, topPending);
    }

    @Override
    @Cacheable(value = CacheConfig.DASHBOARDS, key = "'admin:' + #auth.name")
    public DashboardAdminDto admin(Authentication auth, String resolvedLanguage) {
        AcademicYearDto year = currentAcademicYearResolver.resolve();
        LocalDate monthStart = YearMonth.now().atDay(1);
        LocalDate monthEnd = monthStart.plusMonths(1);
        ReportQueryRepository.AdminStatsRow row = queryRepository.adminStats(monthStart, monthEnd, year.startDate(), year.endDate());
        long pendingReviews = reviewService.summary(auth).pendingCount();
        Double reviewAvgHours = reportService.reviewEfficiency().overallAvgHours();

        AdminStatsDto stats = new AdminStatsDto(row.totalStudents(), row.activeStudents(), row.totalProjects(),
                row.activeProjects(), pendingReviews, row.approvedThisMonth(), row.totalServiceHoursThisYear(), reviewAvgHours);

        List<ReviewQueueItemDto> pendingReviewItems = reviewService.queue(PageRequest.of(0, 5), null, null, null, auth, resolvedLanguage).getContent();
        List<ProjectDto> activeProjects = projectService
                .list(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "startAt")), null, null, null, auth, resolvedLanguage)
                .getContent().stream()
                .filter(p -> Set.of("REGISTRATION_OPEN", "IN_PROGRESS").contains(p.status()))
                .limit(3)
                .toList();

        return new DashboardAdminDto(Instant.now(), stats, pendingReviewItems, activeProjects);
    }

    private ScopeDescriptor resolveScope(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean grantAll = principal.roleCodes().contains("admin") || principal.roleCodes().contains("sysadmin");
        return dataScopeResolver.resolve(principal, SCOPE_RESOURCE_TYPE, grantAll);
    }
}
