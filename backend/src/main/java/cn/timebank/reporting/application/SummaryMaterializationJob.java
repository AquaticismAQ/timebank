package cn.timebank.reporting.application;

import cn.timebank.reporting.infrastructure.ReportQueryRepository;
import cn.timebank.reporting.infrastructure.SummaryRepository;
import cn.timebank.rules.application.RuleResolutionService;
import cn.timebank.rules.application.dto.EffectiveRule;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.dto.AcademicYearDto;
import cn.timebank.student.application.dto.GradeDto;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B5.8/D5: this codebase's first scheduled job. Materializes
 * participation/compliance by grade into `grade_participation_summary` /
 * `grade_compliance_summary` (V9) so {@link ReportServiceImpl} serves them
 * without recomputing over the full ledger on every request
 * (Design.md §16.2). Runs hourly — a term's participation/compliance
 * figures do not need minute freshness, and `generatedAt` on the response
 * tells the viewer exactly how stale the number is.
 *
 * {@code @SchedulerLock} makes this safe under Design.md §17.1's
 * two-instance topology even though only one instance runs today.
 */
@Component
class SummaryMaterializationJob {

    private static final Logger log = LoggerFactory.getLogger(SummaryMaterializationJob.class);

    private final ReportQueryRepository queryRepository;
    private final SummaryRepository summaryRepository;
    private final AcademicStructureService academicStructureService;
    private final RuleResolutionService ruleResolutionService;
    private final ReportServiceImpl reportService;
    private final CurrentAcademicYearResolver currentAcademicYearResolver;

    SummaryMaterializationJob(ReportQueryRepository queryRepository, SummaryRepository summaryRepository,
                               AcademicStructureService academicStructureService, RuleResolutionService ruleResolutionService,
                               ReportServiceImpl reportService, CurrentAcademicYearResolver currentAcademicYearResolver) {
        this.queryRepository = queryRepository;
        this.summaryRepository = summaryRepository;
        this.academicStructureService = academicStructureService;
        this.ruleResolutionService = ruleResolutionService;
        this.reportService = reportService;
        this.currentAcademicYearResolver = currentAcademicYearResolver;
    }

    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "gradeSummaryMaterialization", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    void materialize() {
        Instant generatedAt = Instant.now();
        AcademicYearDto year = currentAcademicYearResolver.resolve();
        List<GradeDto> grades = academicStructureService.listGrades("zh-CN");

        Map<String, ReportQueryRepository.GradeParticipationRow> participation = new HashMap<>();
        queryRepository.participationByGrade(year.startDate(), year.endDate())
                .forEach(r -> participation.put(r.gradeId(), r));
        for (GradeDto grade : grades) {
            var row = participation.get(grade.id());
            int total = row == null ? 0 : row.totalStudents();
            int participated = row == null ? 0 : row.participatedStudents();
            summaryRepository.upsertParticipation(grade.id(), year.id(), total, participated, generatedAt);
        }

        EffectiveRule rule = ruleResolutionService.resolveEffectiveAt(Instant.now()).orElse(null);
        if (rule == null) {
            log.warn("Skipping compliance summary materialization: no rule is currently in effect");
        } else {
            Map<String, GradeDto> gradesById = new HashMap<>();
            grades.forEach(g -> gradesById.put(g.id(), g));
            Map<String, int[]> compliance = reportService.computeCompletionFigures(year, rule, gradesById);
            for (GradeDto grade : grades) {
                int[] f = compliance.getOrDefault(grade.id(), new int[]{0, 0});
                int required = rule.annualRequiredMinutesForGradeLevel(grade.sequence());
                summaryRepository.upsertCompliance(grade.id(), year.id(), f[0], f[1], required, generatedAt);
            }
        }

        log.info("Grade summary materialization complete for academic year {} ({} grades)", year.id(), grades.size());
    }
}
