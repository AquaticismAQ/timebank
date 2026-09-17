package cn.timebank.reporting.application;

import cn.timebank.common.error.ApiErrorCode;
import cn.timebank.common.error.ApiException;
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
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue 093 §1. The Reports page's academic-year dropdown was dead in both
 * layers, and the way a fix of this shape goes wrong is that the parameter
 * is accepted and then ignored — which looks identical to working until you
 * compare two years' numbers.
 *
 * <p>So this test never asserts "the call succeeded". The fake
 * {@link ReportQueryRepository} below holds one fixed data set spanning two
 * academic years and filters it by the date window it is <em>actually
 * handed</em>, exactly as the SQL's {@code between :start and :end} does.
 * Every assertion is therefore about two years producing <em>different</em>
 * figures from the same data; if {@code ReportServiceImpl} went back to
 * resolving the current year regardless of the argument, both years would
 * return the same numbers and every one of these would fail.</p>
 *
 * <p>The SQL itself is not exercised here (no database in {@code mvn test})
 * — {@code DashboardReportingIT} covers the real queries.</p>
 */
class ReportYearScopeTest {

    private static final AcademicYearDto PAST_YEAR =
            new AcademicYearDto("ay_2024_2025", "2024-2025", LocalDate.of(2024, 9, 1), LocalDate.of(2025, 8, 31), false);
    private static final AcademicYearDto CURRENT_YEAR =
            new AcademicYearDto("ay_2025_2026", "2025-2026", LocalDate.of(2025, 9, 1), LocalDate.of(2026, 8, 31), true);

    private static final GradeDto GRADE_7 = new GradeDto("grade_7", "grade_7", "Grade 7", 7);

    /** Middle-school annual requirement 1,500 minutes — the only rule figure these assertions depend on. */
    private static final EffectiveRule RULE = new EffectiveRule(
            "rule_test", 30, 60, 100, 1500, 4500, 4500, 13500, 600, 1200, 1800, 2400);

    /**
     * Three grade-7 students, three service records, deliberately split
     * across the two years: one student served only in 2024-2025, two only
     * in 2025-2026. Nobody served in both, so no figure can coincide by
     * accident.
     */
    private record Record(String studentId, LocalDate serviceDate, int minutes) {
    }

    private static final List<String> STUDENTS = List.of("stu_a", "stu_b", "stu_c");
    private static final List<Record> RECORDS = List.of(
            new Record("stu_a", LocalDate.of(2025, 3, 10), 600),    // 2024-2025 only, below the 1,500 requirement
            new Record("stu_b", LocalDate.of(2025, 10, 4), 1800),   // 2025-2026 only, above it
            new Record("stu_c", LocalDate.of(2026, 1, 20), 3000));  // 2025-2026 only, above it

    /** Two projects, one scheduled in each year. */
    private record Project(String id, String title, LocalDate startsOn, long participants, long minutes) {
    }

    private static final List<Project> PROJECTS = List.of(
            new Project("prj_past", "Last Year Clean-up", LocalDate.of(2025, 4, 2), 9, 540),
            new Project("prj_now", "This Year Library Duty", LocalDate.of(2025, 11, 8), 4, 240));

    private final ReportQueryRepository queryRepository = fakeQueryRepository();
    private final SummaryRepository summaryRepository = mock(SummaryRepository.class);
    private final AcademicStructureService academicStructureService = mock(AcademicStructureService.class);
    private final RuleResolutionService ruleResolutionService = mock(RuleResolutionService.class);
    private final UserService userService = mock(UserService.class);

    private final ReportServiceImpl reportService = new ReportServiceImpl(
            queryRepository, summaryRepository, academicStructureService, ruleResolutionService, userService,
            new ReportAcademicYearResolver(academicStructureService));

    {
        when(academicStructureService.listAcademicYears()).thenReturn(List.of(PAST_YEAR, CURRENT_YEAR));
        when(academicStructureService.listGrades(anyString())).thenReturn(List.of(GRADE_7));
        when(ruleResolutionService.resolveEffectiveAt(any())).thenReturn(Optional.of(RULE));
        // No materialized summary for either year: both go down the live-computation
        // path, which is the one the date window reaches.
        when(summaryRepository.participationForYear(anyString())).thenReturn(List.of());
        when(summaryRepository.complianceForYear(anyString())).thenReturn(List.of());
        when(userService.getById(anyString())).thenThrow(new IllegalStateException("no user directory in this test"));
    }

    @Test
    void participationCountsOnlyTheStudentsWhoServedInTheYearAsked() {
        ParticipationReportDto past = reportService.participation("en", PAST_YEAR.id());
        ParticipationReportDto current = reportService.participation("en", CURRENT_YEAR.id());

        assertThat(past.academicYearId()).isEqualTo(PAST_YEAR.id());
        assertThat(current.academicYearId()).isEqualTo(CURRENT_YEAR.id());
        // Same three students in the denominator both years; a different one
        // third vs. two thirds of them took part.
        assertThat(grade7(past).totalStudents()).isEqualTo(3);
        assertThat(grade7(current).totalStudents()).isEqualTo(3);
        assertThat(grade7(past).participatedStudents()).isEqualTo(1);
        assertThat(grade7(current).participatedStudents()).isEqualTo(2);
    }

    @Test
    void anAbsentYearMeansTheCurrentYearAndNeverEveryYearAtOnce() {
        ParticipationReportDto omitted = reportService.participation("en", null);
        ParticipationReportDto blank = reportService.participation("en", "   ");

        assertThat(omitted.academicYearId()).isEqualTo(CURRENT_YEAR.id());
        assertThat(blank.academicYearId()).isEqualTo(CURRENT_YEAR.id());
        // 2, not 3. All three students have a record somewhere in the fixture,
        // so "3" is what an unscoped query would return — the exact silent
        // widening this must not do.
        assertThat(grade7(omitted).participatedStudents()).isEqualTo(2);
        assertThat(grade7(blank).participatedStudents()).isEqualTo(2);
    }

    @Test
    void completionJudgesEachYearAgainstThatYearsServiceOnly() {
        CompletionReportDto past = reportService.completion("en", PAST_YEAR.id());
        CompletionReportDto current = reportService.completion("en", CURRENT_YEAR.id());

        assertThat(grade7Completion(past).requiredMinutes()).isEqualTo(1500);
        // stu_a's 600 minutes fall short; stu_b (1,800) and stu_c (3,000) clear it.
        assertThat(grade7Completion(past).completedStudents()).isZero();
        assertThat(grade7Completion(current).completedStudents()).isEqualTo(2);
        assertThat(grade7Completion(past).completionRate()).isNotEqualTo(grade7Completion(current).completionRate());
    }

    @Test
    void serviceHoursBucketsAreDisjointBetweenTwoYears() {
        ServiceHoursReportDto past = reportService.serviceHours(PAST_YEAR.id());
        ServiceHoursReportDto current = reportService.serviceHours(CURRENT_YEAR.id());

        assertThat(past.byMonth()).extracting(ServiceHoursReportDto.MonthFigure::month).containsExactly("2025-03");
        assertThat(current.byMonth()).extracting(ServiceHoursReportDto.MonthFigure::month)
                .containsExactly("2025-10", "2026-01");
        assertThat(past.academicYearId()).isEqualTo(PAST_YEAR.id());
        assertThat(current.academicYearId()).isEqualTo(CURRENT_YEAR.id());
    }

    @Test
    void reviewEfficiencyCountsOnlyDecisionsTakenInTheYearAsked() {
        ReviewEfficiencyReportDto past = reportService.reviewEfficiency(PAST_YEAR.id());
        ReviewEfficiencyReportDto current = reportService.reviewEfficiency(CURRENT_YEAR.id());

        assertThat(past.totalDecisions()).isEqualTo(1);
        assertThat(current.totalDecisions()).isEqualTo(2);
        assertThat(past.academicYearId()).isEqualTo(PAST_YEAR.id());
        assertThat(current.academicYearId()).isEqualTo(CURRENT_YEAR.id());
    }

    @Test
    void topProjectsListsOnlyProjectsScheduledInTheYearAsked() {
        TopProjectsReportDto past = reportService.topProjects(PAST_YEAR.id());
        TopProjectsReportDto current = reportService.topProjects(CURRENT_YEAR.id());

        assertThat(past.topProjects()).extracting(TopProjectsReportDto.ProjectFigure::projectId)
                .containsExactly("prj_past");
        assertThat(current.topProjects()).extracting(TopProjectsReportDto.ProjectFigure::projectId)
                .containsExactly("prj_now");
        assertThat(past.academicYearId()).isEqualTo(PAST_YEAR.id());
        assertThat(current.academicYearId()).isEqualTo(CURRENT_YEAR.id());
    }

    @Test
    void anUnknownYearIsA404OnEveryReportRatherThanQuietlyBecomingTheCurrentOne() {
        List<Runnable> calls = List.of(
                () -> reportService.participation("en", "ay_no_such"),
                () -> reportService.completion("en", "ay_no_such"),
                () -> reportService.serviceHours("ay_no_such"),
                () -> reportService.reviewEfficiency("ay_no_such"),
                () -> reportService.topProjects("ay_no_such"));

        for (Runnable call : calls) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiErrorCode.RESOURCE_NOT_FOUND));
        }
    }

    // ── the fake ────────────────────────────────────────────────────────────

    private static ParticipationReportDto.GradeFigure grade7(ParticipationReportDto report) {
        return report.byGrade().stream().filter(g -> g.gradeId().equals("grade_7")).findFirst().orElseThrow();
    }

    private static CompletionReportDto.GradeFigure grade7Completion(CompletionReportDto report) {
        return report.byGrade().stream().filter(g -> g.gradeId().equals("grade_7")).findFirst().orElseThrow();
    }

    private static boolean inWindow(LocalDate date, LocalDate start, LocalDate end) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    /**
     * Filters the fixture by whatever window it is given — the point of the
     * whole test. A {@code when(...).thenReturn(fixedList)} stub here would
     * make every assertion above pass whether or not the year is honoured.
     */
    private static ReportQueryRepository fakeQueryRepository() {
        ReportQueryRepository repository = mock(ReportQueryRepository.class);

        when(repository.participationByGrade(any(), any())).thenAnswer(invocation -> {
            LocalDate start = invocation.getArgument(0);
            LocalDate end = invocation.getArgument(1);
            Set<String> participated = new LinkedHashSet<>();
            RECORDS.stream().filter(r -> inWindow(r.serviceDate(), start, end)).forEach(r -> participated.add(r.studentId()));
            return List.of(new ReportQueryRepository.GradeParticipationRow("grade_7", STUDENTS.size(), participated.size()));
        });

        when(repository.completedMinutesByStudent(any(), any())).thenAnswer(invocation -> {
            LocalDate start = invocation.getArgument(0);
            LocalDate end = invocation.getArgument(1);
            List<ReportQueryRepository.StudentCompletionRow> rows = new ArrayList<>();
            for (String studentId : STUDENTS) {
                int minutes = RECORDS.stream()
                        .filter(r -> r.studentId().equals(studentId) && inWindow(r.serviceDate(), start, end))
                        .mapToInt(Record::minutes).sum();
                rows.add(new ReportQueryRepository.StudentCompletionRow("grade_7", studentId, minutes));
            }
            return rows;
        });

        when(repository.serviceMinutesByMonth(any(), any())).thenAnswer(invocation -> {
            LocalDate start = invocation.getArgument(0);
            LocalDate end = invocation.getArgument(1);
            Map<String, Integer> byMonth = new LinkedHashMap<>();
            RECORDS.stream().filter(r -> inWindow(r.serviceDate(), start, end))
                    .sorted((a, b) -> a.serviceDate().compareTo(b.serviceDate()))
                    .forEach(r -> byMonth.merge(YearMonth.from(r.serviceDate()).toString(), r.minutes(), Integer::sum));
            return byMonth.entrySet().stream()
                    .map(e -> new ReportQueryRepository.MonthMinutesRow(e.getKey(), e.getValue())).toList();
        });

        // One decision per record, taken on the day the service happened — so
        // the same two-year split applies to the decision timestamps.
        when(repository.reviewEfficiencyByReviewer(any(), any())).thenAnswer(invocation -> {
            LocalDate start = invocation.getArgument(0);
            LocalDate end = invocation.getArgument(1);
            long decisions = RECORDS.stream().filter(r -> inWindow(r.serviceDate(), start, end)).count();
            return decisions == 0 ? List.of()
                    : List.of(new ReportQueryRepository.ReviewerFigureRow("usr_reviewer", decisions, 4.0));
        });

        when(repository.topProjects(anyInt(), any(), any())).thenAnswer(invocation -> {
            LocalDate start = invocation.getArgument(1);
            LocalDate end = invocation.getArgument(2);
            return PROJECTS.stream().filter(p -> inWindow(p.startsOn(), start, end))
                    .map(p -> new ReportQueryRepository.TopProjectRow(p.id(), p.title(), p.participants(), p.minutes()))
                    .toList();
        });

        return repository;
    }
}
