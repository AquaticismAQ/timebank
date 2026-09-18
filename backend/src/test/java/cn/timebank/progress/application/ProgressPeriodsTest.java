package cn.timebank.progress.application;

import cn.timebank.account.application.AccountService;
import cn.timebank.progress.application.dto.ProgressDto;
import cn.timebank.progress.domain.StudentProgress;
import cn.timebank.progress.infrastructure.StudentProgressRepository;
import cn.timebank.rules.application.RuleResolutionService;
import cn.timebank.rules.application.dto.EffectiveRule;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.servicerecord.application.ServiceRecordService;
import cn.timebank.servicerecord.application.dto.AnnualProgressSummary;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.StudentService;
import cn.timebank.student.application.dto.AcademicYearDto;
import cn.timebank.student.application.dto.StudentDetailDto;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue 104 — {@code GET /students/{id}/progress} carries two different
 * periods, and they must not be confused for one another.
 *
 * <p>This went unnoticed for months because the seed dataset sat entirely
 * inside one academic year, so every student's lifetime total <em>equalled</em>
 * their annual one and any code mixing them was right by coincidence. These
 * tests exist to make that coincidence impossible to rely on again: the fixture
 * below deliberately gives the student 8.5 annual hours against 304.5 lifetime,
 * so a figure taken from the wrong period cannot match the expected value.</p>
 */
class ProgressPeriodsTest {

    private static final int ANNUAL_MINUTES = 510;      // 8.5h this year
    private static final int LIFETIME_MINUTES = 18_270; // 304.5h all-time

    private final StudentProgressRepository repository = mock(StudentProgressRepository.class);
    private final RuleResolutionService rules = mock(RuleResolutionService.class);
    private final AcademicStructureService academicStructure = mock(AcademicStructureService.class);
    private final StudentService students = mock(StudentService.class);
    private final ServiceRecordService serviceRecords = mock(ServiceRecordService.class);
    private final AccountService accounts = mock(AccountService.class);

    private final ProgressServiceImpl service =
            new ProgressServiceImpl(repository, rules, academicStructure, students, serviceRecords, accounts);

    private ProgressDto progressFor(StudentProgress stored) {
        when(rules.resolveEffectiveAt(any())).thenReturn(Optional.of(new EffectiveRule(
                "rule_default_2025", 30, 60, 100,
                1500, 4500,     // middle: 25h a year, 75h cumulative
                4500, 18000,    // high:   75h a year, 300h cumulative
                1500, 4500, 9000, 18000)));
        when(academicStructure.listAcademicYears()).thenReturn(List.of(new AcademicYearDto(
                "ay_2026_2027", "2026-2027", LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31), true)));
        when(students.getDetail(anyString(), anyString())).thenReturn(new StudentDetailDto(
                "std_1", "HKTZ4HKV", "Reagan W.", "grade_11", 11, "class_11B", "B",
                "ACTIVE", 2027, LocalDate.of(2021, 9, 1), 1L, Instant.now(), Instant.now()));
        when(serviceRecords.summarizeForProgress(anyString(), any(), any()))
                .thenReturn(new AnnualProgressSummary(ANNUAL_MINUTES, 0));
        when(accounts.getBalanceForStudent(anyString())).thenReturn(30_450);
        when(repository.findById("std_1")).thenReturn(Optional.ofNullable(stored));

        Authentication auth = mock(Authentication.class);
        CookieSessionUserDetails principal = mock(CookieSessionUserDetails.class);
        when(principal.studentId()).thenReturn("std_1");
        when(auth.getPrincipal()).thenReturn(principal);
        return service.getProgress("std_1", auth);
    }

    private static StudentProgress stored(int lifetimeMinutes, String honourLevel) {
        StudentProgress p = new StudentProgress("std_1", Instant.now());
        p.setTotalCreditedMinutes(lifetimeMinutes);
        p.setHonourLevel(honourLevel);
        return p;
    }

    @Test
    void theStageFigureIsLifetimeNotThisYear() {
        ProgressDto dto = progressFor(stored(LIFETIME_MINUTES, "EXCELLENT"));

        // The defect: the page divided `completedMinutes` by
        // `stageRequiredMinutes`, rendering "8.5 / 300" for a student past 300.
        assertThat(dto.stageCompletedMinutes()).isEqualTo(LIFETIME_MINUTES);
        assertThat(dto.completedMinutes()).isEqualTo(ANNUAL_MINUTES);
        assertThat(dto.stageCompletedMinutes()).isNotEqualTo(dto.completedMinutes());
        // …and the student has in fact met the 300h cumulative requirement.
        assertThat(dto.stageCompletedMinutes()).isGreaterThanOrEqualTo(dto.stageRequiredMinutes());
    }

    @Test
    void theAnnualFiguresStayScopedToTheYear() {
        ProgressDto dto = progressFor(stored(LIFETIME_MINUTES, "EXCELLENT"));

        assertThat(dto.annualRequiredMinutes()).isEqualTo(4500);          // grade 11 -> high school
        assertThat(dto.gapMinutes()).isEqualTo(4500 - ANNUAL_MINUTES);    // measured against the year, not the stage
    }

    @Test
    void theRecognitionLevelComesFromTheStoredLifetimeTotal() {
        // A tier derived from this year's 8.5h would be "none"; the stored
        // lifetime projection says excellent, and that is what ships.
        assertThat(progressFor(stored(LIFETIME_MINUTES, "EXCELLENT")).recognitionLevel()).isEqualTo("excellent");
    }

    @Test
    void aStudentWithNoApprovalsReadsZeroRatherThanFailing() {
        ProgressDto dto = progressFor(null);

        assertThat(dto.stageCompletedMinutes()).isZero();
        assertThat(dto.recognitionLevel()).isEqualTo("none");
    }

    @Test
    void anUnsetHonourLevelReadsNoneRatherThanThrowing() {
        // `honour_level` is a nullable column, and the old code called
        // `.toLowerCase()` on it unguarded — a row in that state produced a 500
        // on a page whose whole job is to report a number.
        ProgressDto dto = progressFor(stored(0, null));

        assertThat(dto.recognitionLevel()).isEqualTo("none");
        assertThat(dto.stageCompletedMinutes()).isZero();
    }
}
