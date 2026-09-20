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
import cn.timebank.reporting.application.dto.CompletionReportDto;
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
    private final ReportAcademicYearResolver academicYearResolver;

    DashboardServiceImpl(ProgressService progressService, AccountService accountService,
                         ServiceRecordService serviceRecordService, ProjectService projectService,
                         NotificationService notificationService, ReviewService reviewService,
                         ReportQueryRepository queryRepository, DataScopeResolver dataScopeResolver,
                         ReportService reportService, ReportAcademicYearResolver academicYearResolver) {
        this.progressService = progressService;
        this.accountService = accountService;
        this.serviceRecordService = serviceRecordService;
        this.projectService = projectService;
        this.notificationService = notificationService;
        this.reviewService = reviewService;
        this.queryRepository = queryRepository;
        this.dataScopeResolver = dataScopeResolver;
        this.reportService = reportService;
        this.academicYearResolver = academicYearResolver;
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
                .list(PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt")), auth, null, null, null, null, null, resolvedLanguage)
                .getContent();
        List<ProjectDto> upcomingProjects = projectService
                .list(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "startAt")),
                        // status, categoryId, gradeId, academicYearId, search — the
                        // dashboard asks for no year, so a student is still pinned to the
                        // current one by the service and staff see every year.
                        null, null, null, null, null, auth, resolvedLanguage)
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
        AcademicYearDto year = academicYearResolver.resolve();
        LocalDate monthStart = YearMonth.now().atDay(1);
        LocalDate monthEnd = monthStart.plusMonths(1);
        // Every figure below is the caller's own scope, not the school's (issue
        // 127). `pendingReviews` already was — it is why a teacher's queue count
        // was the one number on this dashboard that differed from an admin's.
        ScopeDescriptor scope = resolveScope(auth);
        ReportQueryRepository.AdminStatsRow row =
                queryRepository.adminStats(monthStart, monthEnd, year.startDate(), year.endDate(), scope);
        long pendingReviews = reviewService.summary(auth).pendingCount();
        // `null` = the current academic year: the dashboard is a "right now"
        // view with no period selector of its own (issue 093 §1).
        Double reviewAvgHours = queryRepository.scopedOverallReviewAvgHours(year.startDate(), year.endDate(), scope);
        Double avgCompletionRate = scopedCompletionRate(scope, resolvedLanguage);

        AdminStatsDto stats = new AdminStatsDto(row.totalStudents(), row.activeStudents(), row.totalProjects(),
                row.activeProjects(), pendingReviews, row.approvedThisMonth(), row.totalServiceHoursThisYear(),
                reviewAvgHours, avgCompletionRate);

        List<ReviewQueueItemDto> pendingReviewItems = reviewService.queue(PageRequest.of(0, 5), null, null, null, auth, resolvedLanguage).getContent();
        List<ProjectDto> activeProjects = projectService
                .list(PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "startAt")),
                        // status, categoryId, gradeId, academicYearId, search — the
                        // dashboard asks for no year, so a student is still pinned to the
                        // current one by the service and staff see every year.
                        null, null, null, null, null, auth, resolvedLanguage)
                .getContent().stream()
                .filter(p -> Set.of("REGISTRATION_OPEN", "IN_PROGRESS").contains(p.status()))
                .limit(3)
                .toList();

        return new DashboardAdminDto(Instant.now(), stats, pendingReviewItems, activeProjects);
    }

    /**
     * The completion figure, averaged over the grades in scope.
     *
     * Built from the existing completion report rather than new SQL: it already
     * computes a rate per grade against that grade's published requirement, and
     * the only question the dashboard adds is *which* grades count. An empty
     * scope-grade set means "everything", matching {@code ScopeDescriptor.All}.
     *
     * Unweighted across grades on purpose — this is "how are my grades doing",
     * not a school total in which a large cohort should outvote a small one.
     * Null where no grade in scope has a requirement to measure against, which
     * the card renders as an em dash rather than 0%.
     */
    private Double scopedCompletionRate(ScopeDescriptor scope, String resolvedLanguage) {
        Set<String> gradeIds = queryRepository.scopeGradeIds(scope);
        List<Double> rates = reportService.completion(resolvedLanguage, null).byGrade().stream()
                .filter(g -> gradeIds.isEmpty() || gradeIds.contains(g.gradeId()))
                .filter(g -> g.requiredMinutes() > 0)
                .map(CompletionReportDto.GradeFigure::completionRate)
                .toList();
        return rates.isEmpty() ? null : rates.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private ScopeDescriptor resolveScope(Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean grantAll = principal.hasSchoolWideVisibility(); // 07-RoleFinetune.md §2.10
        return dataScopeResolver.resolve(principal, SCOPE_RESOURCE_TYPE, grantAll);
    }
}
