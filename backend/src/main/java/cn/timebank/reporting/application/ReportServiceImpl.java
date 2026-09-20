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
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
    private final ReportAcademicYearResolver academicYearResolver;

    ReportServiceImpl(ReportQueryRepository queryRepository, SummaryRepository summaryRepository,
                       AcademicStructureService academicStructureService, RuleResolutionService ruleResolutionService,
                       UserService userService, ReportAcademicYearResolver academicYearResolver) {
        this.queryRepository = queryRepository;
        this.summaryRepository = summaryRepository;
        this.academicStructureService = academicStructureService;
        this.ruleResolutionService = ruleResolutionService;
        this.userService = userService;
        this.academicYearResolver = academicYearResolver;
    }

    @Override
    public ParticipationReportDto participation(String resolvedLanguage, String academicYearId) {
        AcademicYearDto year = academicYearResolver.resolve(academicYearId);
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
    public CompletionReportDto completion(String resolvedLanguage, String academicYearId) {
        AcademicYearDto year = academicYearResolver.resolve(academicYearId);
        Map<String, GradeDto> grades = gradesByIdInOrder(resolvedLanguage);

        List<SummaryRepository.ComplianceSummaryRow> materialized = summaryRepository.complianceForYear(year.id());
        Instant generatedAt;
        Map<String, int[]> figuresByGrade; // [total, completed]
        Map<String, Integer> requiredMinutesByGrade;
        if (!materialized.isEmpty()) {
            generatedAt = materialized.get(0).generatedAt();
            figuresByGrade = new LinkedHashMap<>();
            requiredMinutesByGrade = new LinkedHashMap<>();
            materialized.forEach(r -> {
                figuresByGrade.put(r.gradeId(), new int[]{r.totalStudents(), r.completedStudents()});
                requiredMinutesByGrade.put(r.gradeId(), r.requiredMinutes());
            });
        } else {
            generatedAt = Instant.now();
            EffectiveRule rule = ruleInEffectDuring(year);
            requiredMinutesByGrade = new LinkedHashMap<>();
            grades.values().forEach(g -> requiredMinutesByGrade.put(g.id(), rule.annualRequiredMinutesForGradeLevel(g.sequence())));
            figuresByGrade = computeCompletionFigures(year, rule, grades);
        }

        List<CompletionReportDto.GradeFigure> byGrade = grades.values().stream()
                .map(g -> {
                    int[] f = figuresByGrade.getOrDefault(g.id(), new int[]{0, 0});
                    double rate = f[0] == 0 ? 0.0 : (double) f[1] / f[0];
                    int required = requiredMinutesByGrade.getOrDefault(g.id(), 0);
                    return new CompletionReportDto.GradeFigure(g.id(), g.name(), f[0], f[1], rate, required);
                }).toList();
        return new CompletionReportDto(generatedAt, year.id(), byGrade);
    }

    /**
     * The annual requirement a year is judged against is the one that was in
     * force <em>during that year</em>, not the one in force today (invariant
     * 8/R7: a rule published this term must not restate what last term's
     * cohort was required to do). For the current or a future year that is
     * still "now"; for a year that has already ended it is the rule effective
     * at its end date. Falls back to today's rule only if no rule resolves
     * for that past date at all — a year that predates the first published
     * rule version is better reported against something than 500'd.
     */
    private EffectiveRule ruleInEffectDuring(AcademicYearDto year) {
        Instant now = Instant.now();
        Instant asOf = year.endDate().isBefore(LocalDate.now())
                ? year.endDate().atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC)
                : now;
        return ruleResolutionService.resolveEffectiveAt(asOf)
                .or(() -> ruleResolutionService.resolveEffectiveAt(now))
                .orElseThrow(() -> new IllegalStateException("No rule is in effect for academic year " + year.id()));
    }

    /** Bounded by active-student count (≤5,000), aggregated once in SQL — the threshold comparison below is arithmetic over an already-grouped result set, not a query per student. */
    Map<String, int[]> computeCompletionFigures(AcademicYearDto year, EffectiveRule rule, Map<String, GradeDto> gradesById) {
        Map<String, int[]> figuresByGrade = new LinkedHashMap<>();
        for (ReportQueryRepository.StudentCompletionRow row : queryRepository.completedMinutesByStudent(year.startDate(), year.endDate())) {
            int[] f = figuresByGrade.computeIfAbsent(row.gradeId(), k -> new int[]{0, 0});
            f[0]++;
            GradeDto grade = gradesById.get(row.gradeId());
            // A row whose grade isn't in the current dictionary (shouldn't happen
            // in practice) is treated as high-school rather than silently miscounted.
            int required = grade == null ? rule.highAnnualRequiredMinutes() : rule.annualRequiredMinutesForGradeLevel(grade.sequence());
            if (row.completedMinutes() >= required) {
                f[1]++;
            }
        }
        return figuresByGrade;
    }

    @Override
    public ServiceHoursReportDto serviceHours(String academicYearId) {
        AcademicYearDto year = academicYearResolver.resolve(academicYearId);
        List<ServiceHoursReportDto.MonthFigure> byMonth = queryRepository.serviceMinutesByMonth(year.startDate(), year.endDate())
                .stream().map(r -> new ServiceHoursReportDto.MonthFigure(r.month(), r.minutes() / 60)).toList();

        // Every day in the year, empty ones included — see the DTO for why a
        // sparse series would draw the wrong shape.
        Map<String, Integer> minutesByDay = queryRepository.serviceMinutesByDay(year.startDate(), year.endDate())
                .stream().collect(Collectors.toMap(ReportQueryRepository.DayMinutesRow::day,
                        ReportQueryRepository.DayMinutesRow::minutes));
        List<ServiceHoursReportDto.DayFigure> byDay = year.startDate()
                .datesUntil(year.endDate().plusDays(1))
                .map(d -> {
                    String key = d.toString();
                    return new ServiceHoursReportDto.DayFigure(key, minutesByDay.getOrDefault(key, 0));
                })
                .toList();

        return new ServiceHoursReportDto(Instant.now(), year.id(), byMonth, byDay);
    }

    @Override
    public ReviewEfficiencyReportDto reviewEfficiency(String academicYearId) {
        AcademicYearDto year = academicYearResolver.resolve(academicYearId);
        List<ReportQueryRepository.ReviewerFigureRow> rows = queryRepository.reviewEfficiencyByReviewer(year.startDate(), year.endDate());

        long totalDecisions = rows.stream().mapToLong(ReportQueryRepository.ReviewerFigureRow::decisions).sum();
        Double overallAvg = totalDecisions == 0 ? null
                : rows.stream().mapToDouble(r -> r.avgHours() * r.decisions()).sum() / totalDecisions;

        List<ReviewEfficiencyReportDto.ReviewerFigure> byReviewer = rows.stream()
                .map(r -> new ReviewEfficiencyReportDto.ReviewerFigure(
                        r.reviewerId(), reviewerName(r.reviewerId()), r.decisions(), r.avgHours()))
                .toList();
        return new ReviewEfficiencyReportDto(Instant.now(), year.id(), totalDecisions, overallAvg, byReviewer);
    }

    @Override
    public TopProjectsReportDto topProjects(String academicYearId) {
        AcademicYearDto year = academicYearResolver.resolve(academicYearId);
        List<TopProjectsReportDto.ProjectFigure> figures =
                queryRepository.topProjects(10, year.startDate(), year.endDate()).stream()
                .map(r -> new TopProjectsReportDto.ProjectFigure(r.projectId(), r.title(), r.participants(), r.totalMinutes()))
                .toList();
        List<TopProjectsReportDto.CategoryFigure> byCategory =
                queryRepository.participationByCategory(year.startDate(), year.endDate()).stream()
                        .map(r -> new TopProjectsReportDto.CategoryFigure(r.categoryId(), r.participants(), r.totalMinutes()))
                        .toList();
        return new TopProjectsReportDto(Instant.now(), year.id(), figures, byCategory);
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
