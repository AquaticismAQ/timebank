package cn.timebank.progress.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.common.error.ApiException;
import cn.timebank.progress.application.dto.ProgressDto;
import cn.timebank.progress.domain.StudentProgress;
import cn.timebank.progress.infrastructure.StudentProgressRepository;
import cn.timebank.rules.application.RuleResolutionService;
import cn.timebank.rules.application.dto.EffectiveRule;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.TimeBankPrincipal;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.AnnualProgressSummary;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.AcademicYearDto;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class ProgressServiceImpl implements ProgressService {

    private final StudentProgressRepository repository;
    private final RuleResolutionService ruleResolutionService;
    private final AcademicStructureService academicStructureService;
    private final StudentService studentService;
    private final ServiceRecordService serviceRecordService;
    private final AccountService accountService;

    ProgressServiceImpl(StudentProgressRepository repository, RuleResolutionService ruleResolutionService,
                         AcademicStructureService academicStructureService, StudentService studentService,
                         ServiceRecordService serviceRecordService, AccountService accountService) {
        this.repository = repository;
        this.ruleResolutionService = ruleResolutionService;
        this.academicStructureService = academicStructureService;
        this.studentService = studentService;
        this.serviceRecordService = serviceRecordService;
        this.accountService = accountService;
    }

    @Override
    @Transactional(readOnly = true)
    public ProgressDto getProgress(String studentId, Authentication auth) {
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        if (principal.studentId() != null && !principal.studentId().equals(studentId)) {
            // Staff roles (principal.studentId() == null) may view any student's
            // progress if they hold progress.read (enforced at the controller) —
            // StudentProgress carries no grade/class columns to scope further in
            // this pass, unlike servicerecord/account's full Specification wiring.
            throw ApiException.notFound("Student not found.");
        }

        EffectiveRule currentRule = ruleResolutionService.resolveEffectiveAt(Instant.now())
                .orElseThrow(() -> new IllegalStateException("No rule is currently in effect"));
        AcademicYearDto currentYear = academicStructureService.listAcademicYears().stream()
                .filter(AcademicYearDto::current).findFirst()
                .orElseThrow(() -> new IllegalStateException("No academic year is marked current"));
        // resolvedLanguage is unused for a raw gradeLevel int (issue 010).
        int gradeLevel = studentService.getDetail(studentId, "en").gradeLevel();
        int annualRequiredMinutes = currentRule.annualRequiredMinutesForGradeLevel(gradeLevel);
        int stageRequiredMinutes = currentRule.stageRequiredMinutesForGradeLevel(gradeLevel);

        AnnualProgressSummary annual = serviceRecordService.summarizeForProgress(
                studentId, currentYear.startDate(), currentYear.endDate());
        int gapMinutes = Math.max(0,
                annualRequiredMinutes - annual.approvedMinutes() - annual.pendingMinutes());
        int timeCoinBalance = accountService.getBalanceForStudent(studentId);

        // The stored lifetime projection, maintained inside the approval
        // transaction (V5__core_loop.sql). It was already being loaded here and
        // everything but the honour level thrown away, which left the client
        // with no cumulative figure at all and the graduation bar dividing this
        // *year's* hours by the lifetime requirement (issue 104 §2.2).
        //
        // Absent row means no approval has ever credited this student, so zero
        // is the honest reading rather than a missing value. `honourLevel` is a
        // nullable column and was dereferenced unguarded, which would have
        // thrown on such a row instead of yielding "none".
        StudentProgress stored = repository.findById(studentId).orElse(null);
        int stageCompletedMinutes = stored == null ? 0 : stored.getTotalCreditedMinutes();
        String recognitionLevel = stored == null || stored.getHonourLevel() == null
                ? "none" : stored.getHonourLevel().toLowerCase();

        return new ProgressDto(studentId, annualRequiredMinutes, stageRequiredMinutes,
                annual.approvedMinutes(), annual.pendingMinutes(), gapMinutes,
                stageCompletedMinutes, timeCoinBalance, recognitionLevel);
    }

    @Override
    @Transactional
    public void addCredit(String studentId, int creditedMinutes, int creditedCentiCoins) {
        StudentProgress progress = repository.findWithLockByStudentId(studentId).orElseGet(() -> {
            // Same narrow, documented first-touch race as AccountServiceImpl —
            // acceptable at this volume (see that class's note).
            StudentProgress created = new StudentProgress(studentId, Instant.now());
            repository.saveAndFlush(created);
            return repository.findWithLockByStudentId(studentId).orElseThrow();
        });

        progress.setTotalCreditedMinutes(progress.getTotalCreditedMinutes() + creditedMinutes);
        progress.setTotalCreditedCentiCoins(progress.getTotalCreditedCentiCoins() + creditedCentiCoins);
        progress.setHonourLevel(honourLevelFor(progress.getTotalCreditedMinutes()));
        progress.setUpdatedAt(Instant.now());
    }

    private String honourLevelFor(int totalCreditedMinutes) {
        return ruleResolutionService.resolveEffectiveAt(Instant.now())
                .map(rule -> {
                    if (totalCreditedMinutes >= rule.honourExcellentMinutes()) return "EXCELLENT";
                    if (totalCreditedMinutes >= rule.honourGoldMinutes()) return "GOLD";
                    if (totalCreditedMinutes >= rule.honourSilverMinutes()) return "SILVER";
                    if (totalCreditedMinutes >= rule.honourBronzeMinutes()) return "BRONZE";
                    return "NONE";
                })
                .orElse("NONE");
    }
}
