package cn.timebank.reporting.application;

import cn.timebank.iam.application.UserService;
import cn.timebank.reporting.application.dto.CompletionReportDto;
import cn.timebank.reporting.application.dto.ParticipationReportDto;
import cn.timebank.reporting.application.dto.ReviewEfficiencyReportDto;
import cn.timebank.reporting.application.dto.ServiceHoursReportDto;
import cn.timebank.reporting.application.dto.TopProjectsReportDto;
import cn.timebank.reporting.infrastructure.ReportQueryRepository;
import cn.timebank.reporting.infrastructure.SummaryRepository;
import cn.timebank.rules.application.RuleResolutionService;
import cn.timebank.rules.application.dto.EffectiveRule;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.dto.AcademicYearDto;
import cn.timebank.student.application.dto.GradeDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class ReportServiceImpl implements ReportService {

    private final ReportQueryRepository queryRepository;
    private final SummaryRepository summaryRepository;
    private final AcademicStructureService academicStructureService;
    private final RuleResolutionService ruleResolutionService;
    private final UserService userService;
    private final CurrentAcademicYearResolver currentAcademicYearResolver;

    ReportServiceImpl(ReportQueryRepository queryRepository, SummaryRepository summaryRepository,
                       AcademicStructureService academicStructureService, RuleResolutionService ruleResolutionService,
                       UserService userService, CurrentAcademicYearResolver currentAcademicYearResolver) {
        this.queryRepository = queryRepository;
        this.summaryRepository = summaryRepository;
        this.academicStructureService = academicStructureService;
        this.ruleResolutionService = ruleResolutionService;
        this.userService = userService;
        this.currentAcademicYearResolver = currentAcademicYearResolver;
    }

    @Override
    public ParticipationReportDto participation(String resolvedLanguage) {
        AcademicYearDto year = currentAcademicYearResolver.resolve();
        Map<String, GradeDto> grades = gradesByIdInOrder(resolvedLanguage);

        List<SummaryRepository.ParticipationSummaryRow> materialized = summaryRepository.participationForYear(year.id());
        Instant generatedAt;
        Map<String, int[]> figuresByGrade; // [total, participated]
        if (!materialized.isEmpty()) {
            generatedAt = materialized.get(0).generatedAt();
            figuresByGrade = new LinkedHashMap<>();
            materialized.forEach(r -> figuresByGrade.put(r.gradeId(), new int[]{r.totalStudents(), r.participatedStudents()}));
        } else {
            generatedAt = Instant.now();
            figuresByGrade = new LinkedHashMap<>();
            queryRepository.participationByGrade(year.startDate(), year.endDate())
                    .forEach(r -> figuresByGrade.put(r.gradeId(), new int[]{r.totalStudents(), r.participatedStudents()}));
        }

        List<ParticipationReportDto.GradeFigure> byGrade = grades.values().stream()
                .map(g -> {
                    int[] f = figuresByGrade.getOrDefault(g.id(), new int[]{0, 0});
                    double rate = f[0] == 0 ? 0.0 : (double) f[1] / f[0];
                    return new ParticipationReportDto.GradeFigure(g.id(), g.name(), f[0], f[1], rate);
                }).toList();
        return new ParticipationReportDto(generatedAt, year.id(), byGrade);
    }

    @Override
    public CompletionReportDto completion(String resolvedLanguage) {
        AcademicYearDto year = currentAcademicYearResolver.resolve();
        Map<String, GradeDto> grades = gradesByIdInOrder(resolvedLanguage);

        List<SummaryRepository.ComplianceSummaryRow> materialized = summaryRepository.complianceForYear(year.id());
        Instant generatedAt;
        int requiredMinutes;
        Map<String, int[]> figuresByGrade; // [total, completed]
        if (!materialized.isEmpty()) {
            generatedAt = materialized.get(0).generatedAt();
            requiredMinutes = materialized.get(0).requiredMinutes();
            figuresByGrade = new LinkedHashMap<>();
            materialized.forEach(r -> figuresByGrade.put(r.gradeId(), new int[]{r.totalStudents(), r.completedStudents()}));
        } else {
            generatedAt = Instant.now();
            EffectiveRule rule = ruleResolutionService.resolveEffectiveAt(Instant.now())
                    .orElseThrow(() -> new IllegalStateException("No rule is currently in effect"));
            requiredMinutes = rule.annualRequiredMinutes();
            figuresByGrade = computeCompletionFigures(year, requiredMinutes);
        }

        int finalRequiredMinutes = requiredMinutes;
        List<CompletionReportDto.GradeFigure> byGrade = grades.values().stream()
                .map(g -> {
                    int[] f = figuresByGrade.getOrDefault(g.id(), new int[]{0, 0});
                    double rate = f[0] == 0 ? 0.0 : (double) f[1] / f[0];
                    return new CompletionReportDto.GradeFigure(g.id(), g.name(), f[0], f[1], rate);
                }).toList();
        return new CompletionReportDto(generatedAt, year.id(), finalRequiredMinutes, byGrade);
    }

    /** Bounded by active-student count (≤5,000), aggregated once in SQL — the threshold comparison below is arithmetic over an already-grouped result set, not a query per student. */
    Map<String, int[]> computeCompletionFigures(AcademicYearDto year, int requiredMinutes) {
        Map<String, int[]> figuresByGrade = new LinkedHashMap<>();
        for (ReportQueryRepository.StudentCompletionRow row : queryRepository.completedMinutesByStudent(year.startDate(), year.endDate())) {
            int[] f = figuresByGrade.computeIfAbsent(row.gradeId(), k -> new int[]{0, 0});
            f[0]++;
            if (row.completedMinutes() >= requiredMinutes) {
                f[1]++;
            }
        }
        return figuresByGrade;
    }

    @Override
    public ServiceHoursReportDto serviceHours() {
        AcademicYearDto year = currentAcademicYearResolver.resolve();
        List<ServiceHoursReportDto.MonthFigure> byMonth = queryRepository.serviceMinutesByMonth(year.startDate(), year.endDate())
                .stream().map(r -> new ServiceHoursReportDto.MonthFigure(r.month(), r.minutes() / 60)).toList();
        return new ServiceHoursReportDto(Instant.now(), year.id(), byMonth);
    }

    @Override
    public ReviewEfficiencyReportDto reviewEfficiency() {
        AcademicYearDto year = currentAcademicYearResolver.resolve();
        List<ReportQueryRepository.ReviewerFigureRow> rows = queryRepository.reviewEfficiencyByReviewer(year.startDate(), year.endDate());

        long totalDecisions = rows.stream().mapToLong(ReportQueryRepository.ReviewerFigureRow::decisions).sum();
        Double overallAvg = totalDecisions == 0 ? null
                : rows.stream().mapToDouble(r -> r.avgHours() * r.decisions()).sum() / totalDecisions;

        List<ReviewEfficiencyReportDto.ReviewerFigure> byReviewer = rows.stream()
                .map(r -> new ReviewEfficiencyReportDto.ReviewerFigure(
                        r.reviewerId(), reviewerName(r.reviewerId()), r.decisions(), r.avgHours()))
                .toList();
        return new ReviewEfficiencyReportDto(Instant.now(), totalDecisions, overallAvg, byReviewer);
    }

    @Override
    public TopProjectsReportDto topProjects() {
        List<TopProjectsReportDto.ProjectFigure> figures = queryRepository.topProjects(10).stream()
                .map(r -> new TopProjectsReportDto.ProjectFigure(r.projectId(), r.title(), r.participants(), r.totalMinutes()))
                .toList();
        return new TopProjectsReportDto(Instant.now(), figures);
    }

    private String reviewerName(String reviewerId) {
        try {
            return userService.getById(reviewerId).displayName();
        } catch (RuntimeException e) {
            return reviewerId;
        }
    }

    private Map<String, GradeDto> gradesByIdInOrder(String resolvedLanguage) {
        Map<String, GradeDto> map = new LinkedHashMap<>();
        academicStructureService.listGrades(resolvedLanguage).stream()
                .sorted((a, b) -> Integer.compare(a.sequence(), b.sequence()))
                .forEach(g -> map.put(g.id(), g));
        return map;
    }
}
