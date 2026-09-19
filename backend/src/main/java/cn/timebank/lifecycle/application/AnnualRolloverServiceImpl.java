package cn.timebank.lifecycle.application;

import cn.timebank.audit.application.AuditService;
import cn.timebank.audit.application.AuditEntry;
import cn.timebank.common.OpaqueIds;
import cn.timebank.lifecycle.application.dto.AnnualRolloverReportDto;
import cn.timebank.lifecycle.infrastructure.AnnualRolloverRepository;
import cn.timebank.lifecycle.infrastructure.AnnualRolloverRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.timebank.lifecycle.infrastructure.AnnualRolloverRepository.EXIT_LEVELS;
import static cn.timebank.lifecycle.infrastructure.AnnualRolloverRepository.PROMOTION_LEVELS_DESC;

/**
 * Design/*&#47;13-AnnualRollover.md §3.
 *
 * <p>Three properties the implementation exists to hold, all of them consequences
 * of running with nobody watching:</p>
 *
 * <ul>
 *   <li><b>One transaction.</b> "Half promoted" is not a state anyone could
 *       diagnose the morning after, so it is not a state that can occur.</li>
 *   <li><b>Idempotent per year.</b> Guarded by a locked read of the target
 *       academic year plus a check for an existing successful run — so a second
 *       trigger, a restart, or two instances starting together cannot promote
 *       twice. A double promotion is silent and irreversible.</li>
 *   <li><b>Graduation before promotion.</b> Grade 8 leaves the school; if
 *       promotion ran first it would become grade 9, which is exactly the
 *       cross-stage migration §2.1 refuses to perform.</li>
 * </ul>
 */
@Service
class AnnualRolloverServiceImpl implements AnnualRolloverService {

    private static final Logger log = LoggerFactory.getLogger(AnnualRolloverServiceImpl.class);

    /** Entry grades: filled by roster import, never by this process (§3.2). */
    private static final List<Integer> INTAKE_LEVELS = List.of(6, 9);

    private final AnnualRolloverRepository repository;
    private final AnnualRolloverRunRepository runRepository;
    private final AuditService auditService;
    private final AcademicYearCalendar calendar;
    /** The work's own transaction, opened explicitly so failure can be recorded after it closes. */
    private final TransactionTemplate workTransaction;

    AnnualRolloverServiceImpl(AnnualRolloverRepository repository,
                               AnnualRolloverRunRepository runRepository,
                               AuditService auditService,
                               AcademicYearCalendar calendar,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.runRepository = runRepository;
        this.auditService = auditService;
        this.calendar = calendar;
        this.workTransaction = new TransactionTemplate(transactionManager);
        workTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public AnnualRolloverReportDto runIfDue(String triggeredBy) {
        return runFor(LocalDate.now(), triggeredBy);
    }

    /**
     * The date is a parameter rather than read from a clock inside, so a test can
     * drive a rollover on any turnover date without waiting for one. There is no
     * {@code Clock} bean in this project — {@code CohortPurgeServiceImpl} calls
     * {@code Instant.now()} directly — so this keeps the seam local rather than
     * introducing a bean nothing else uses.
     *
     * <p><b>Not transactional itself</b> — it opens and closes the work's transaction
     * explicitly, because recording a failure has to happen once that transaction
     * is over.
     *
     * <p>The rollover holds {@code SELECT ... FOR UPDATE} on the target
     * {@code academic_year} row for its whole duration, and InnoDB takes a lock on
     * a referenced parent row when a child row is written. {@code
     * annual_rollover_run} carries a foreign key to that year, so writing the
     * failure from a separate transaction while the work's transaction still holds
     * the parent blocks until the lock wait times out — a fifty-second stall
     * ending in a second failure that buries the first. Writing it after the
     * rollback has released the lock costs nothing and cannot contend.</p>
     */
    AnnualRolloverReportDto runFor(LocalDate today, String triggeredBy) {
        int targetStart = academicYearStartFor(today);
        String targetId = "ay_" + targetStart + "_" + (targetStart + 1);
        String previousId = "ay_" + (targetStart - 1) + "_" + targetStart;

        // The target year has to exist before anything references it: the run row
        // carries a foreign key to it, and a deployment seeded two years ago has
        // no row for the year now starting. Creating it is therefore the first
        // step, not part of the work the run row is recording.
        repository.ensureAcademicYear(targetId, targetStart,
                calendar.startOf(targetStart), calendar.endOf(targetStart));

        String runId = OpaqueIds.generate("aro");
        runRepository.start(runId, targetId, triggeredBy, Instant.now());

        try {
            return workTransaction.execute(status -> runWork(runId, targetId, previousId, targetStart, triggeredBy));
        } catch (RuntimeException e) {
            // The work's transaction has ended and released its locks by the time
            // this runs, so the failure record is written without contention — and
            // it survives, which is the whole point of the table (V30).
            log.error("Annual rollover into {} failed", targetId, e);
            runRepository.fail(runId, e.toString());
            throw e;
        }
    }

    /** The rollover proper. Runs inside {@code workTransaction}, so it is all-or-nothing. */
    private AnnualRolloverReportDto runWork(String runId, String targetId, String previousId,
                                             int targetStart, String triggeredBy) {
        repository.lockAcademicYear(targetId);

            if (repository.hasSucceededFor(targetId)) {
                runRepository.skip(runId);
                return skipped(runId, targetId);
            }

            int classesCreated = carryClassesForward(previousId, targetId, targetStart);
            int graduated = repository.graduateExitGrades();

            Map<String, Integer> promotedByGrade = new LinkedHashMap<>();
            int promoted = 0;
            for (int level : PROMOTION_LEVELS_DESC) {
                int moved = repository.promoteGrade(level, targetId);
                if (moved > 0) {
                    promotedByGrade.put("grade_" + (level + 1), moved);
                    promoted += moved;
                }
            }

            // Anyone still sitting in a grade promotion should have emptied had
            // no counterpart class in the target year. Counted, not swallowed.
            int skipped = 0;
            for (int level : PROMOTION_LEVELS_DESC) {
                skipped += repository.countStudentsAt(level);
            }

            int logins = repository.deactivateGraduatedLogins();
            // §3.4: the live snapshot follows the student; historical rows do not.
            int accounts = repository.resyncAccountSnapshots();
            repository.markCurrent(targetId);
            int inconsistent = repository.countInconsistentGrades(targetStart);

            List<String> emptyIntake = new ArrayList<>();
            for (int level : INTAKE_LEVELS) {
                if (repository.countStudentsAt(level) == 0) {
                    emptyIntake.add("grade_" + level);
                }
            }

            runRepository.complete(runId, promoted, graduated, logins, accounts,
                    classesCreated, skipped, inconsistent);
            auditService.record(new AuditEntry(triggeredBy, null, "lifecycle.annualRollover",
                    "academicYear", targetId, null,
                    Map.of("promoted", promoted, "graduated", graduated, "classesCreated", classesCreated,
                            "accountsResynced", accounts, "skipped", skipped,
                            "inconsistentGrades", inconsistent), null));

            log.info("Annual rollover into {}: {} promoted, {} graduated, {} accounts resynced, "
                            + "{} classes created, {} skipped, {} inconsistent",
                    targetId, promoted, graduated, accounts, classesCreated, skipped, inconsistent);

            return new AnnualRolloverReportDto(runId, targetId, "RAN", promoted, graduated, logins,
                    accounts, classesCreated, skipped, inconsistent, promotedByGrade, emptyIntake,
                    emptyIntake.isEmpty() ? null
                            : "Awaiting roster import for " + String.join(", ", emptyIntake));
    }

    /**
     * Which academic year a date belongs to, from the configured boundary
     * ({@link AcademicYearCalendar}) rather than a hardcoded 1 September. The
     * seed bootstraps its first two years on the default, so an unconfigured
     * deployment behaves exactly as before.
     */
    private int academicYearStartFor(LocalDate today) {
        return calendar.academicYearStartFor(today);
    }

    /**
     * Creates the target year's classes (§4): each previous class advances one
     * grade keeping its suffix, and the two intake grades are recreated with
     * the suffixes they used last year so the roster import has somewhere to
     * put new students.
     */
    private int carryClassesForward(String previousYearId, String targetYearId, int targetStart) {
        List<AnnualRolloverRepository.ClassRow> previous = repository.classesIn(previousYearId);
        int created = 0;
        for (AnnualRolloverRepository.ClassRow row : previous) {
            int level = levelOf(row.gradeId());
            if (level < 0) {
                continue;
            }
            if (!EXIT_LEVELS.contains(level)) {
                created += createFor(level + 1, row, targetYearId, targetStart);
            }
            if (INTAKE_LEVELS.contains(level)) {
                // Same grade, next year: the intake reuses last year's suffixes.
                created += createFor(level, row, targetYearId, targetStart);
            }
        }
        return created;
    }

    private int createFor(int level, AnnualRolloverRepository.ClassRow source, String yearId, int targetStart) {
        // The code carries the grade prefix ("9A", "9-Mars"); the suffix is what
        // survives, so strip the leading digits rather than assuming a length.
        String suffix = source.code().replaceFirst("^\\d+", "");
        String code = level + suffix;
        String id = "cls_" + targetStart + "_" + code.replaceAll("[^A-Za-z0-9]", "_");
        return repository.createClass(id, code, source.name(), "grade_" + level, yearId);
    }

    private int levelOf(String gradeId) {
        try {
            return Integer.parseInt(gradeId.substring("grade_".length()));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private AnnualRolloverReportDto skipped(String runId, String targetId) {
        log.info("Annual rollover into {} skipped: already completed", targetId);
        return new AnnualRolloverReportDto(runId, targetId, "SKIPPED", 0, 0, 0, 0, 0, 0, 0,
                Map.of(), List.of(), "This academic year has already been rolled over.");
    }
}
