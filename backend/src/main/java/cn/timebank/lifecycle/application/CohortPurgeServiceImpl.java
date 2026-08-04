package cn.timebank.lifecycle.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.file.application.FileService;
import cn.timebank.lifecycle.application.dto.PurgeRunSummaryDto;
import cn.timebank.lifecycle.application.dto.PurgeRunSummaryDto.StudentPurgeDetail;
import cn.timebank.lifecycle.infrastructure.PurgeQueryRepository;
import cn.timebank.lifecycle.infrastructure.PurgeQueryRepository.StudentRowCounts;
import cn.timebank.lifecycle.infrastructure.PurgeRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
class CohortPurgeServiceImpl implements CohortPurgeService {

    private static final Logger log = LoggerFactory.getLogger(CohortPurgeServiceImpl.class);

    /** Names which retention rule this run applied (Design.md §16.3 point 3) — bump if the graduation+1y formula itself ever changes. */
    static final String POLICY_VERSION = "retention-graduation-plus-1y-v1";

    private final PurgeQueryRepository purgeQueryRepository;
    private final PurgeRunRepository purgeRunRepository;
    private final PurgeStudentTransaction purgeStudentTransaction;
    private final FileService fileService;

    CohortPurgeServiceImpl(PurgeQueryRepository purgeQueryRepository, PurgeRunRepository purgeRunRepository,
                            PurgeStudentTransaction purgeStudentTransaction, FileService fileService) {
        this.purgeQueryRepository = purgeQueryRepository;
        this.purgeRunRepository = purgeRunRepository;
        this.purgeStudentTransaction = purgeStudentTransaction;
        this.fileService = fileService;
    }

    /** No graduation year is ever legitimately 0 — used only to mark a "ran, found nothing eligible" heartbeat row, distinguishable in {@code purge_run} from any real cohort. */
    private static final int NO_ELIGIBLE_COHORT_SENTINEL = 0;

    @Override
    public List<PurgeRunSummaryDto> run(boolean dryRun, String actorId) {
        LocalDate today = LocalDate.now();
        List<Integer> eligibleYears = purgeQueryRepository.findEligibleGraduationYears(today);
        List<PurgeRunSummaryDto> summaries = new ArrayList<>();
        for (int year : eligibleYears) {
            summaries.add(runForCohort(year, today, dryRun, actorId));
        }
        if (eligibleYears.isEmpty()) {
            // The non-execution watchdog (B6.4/LifecycleWatchdogJob) can only tell
            // "ran, nothing eligible today" apart from "never ran" if a run row
            // exists either way — without this, the overwhelmingly common case
            // (no cohort due yet) would leave zero evidence the job executed at
            // all, which is exactly the compliance failure mode being guarded
            // against. Caught live: see 00-Status.md §19.
            summaries.add(runForCohort(NO_ELIGIBLE_COHORT_SENTINEL, today, dryRun, actorId));
        }
        return summaries;
    }

    private PurgeRunSummaryDto runForCohort(int graduationYear, LocalDate today, boolean dryRun, String actorId) {
        String runId = OpaqueIds.generate("purge");
        purgeRunRepository.start(runId, graduationYear, dryRun, POLICY_VERSION, actorId, Instant.now());

        List<String> studentIds = purgeQueryRepository.findEligibleStudentIds(graduationYear, today);
        List<StudentPurgeDetail> details = new ArrayList<>();
        int totalRows = 0;
        int totalObjects = 0;
        int studentsPurged = 0;
        try {
            for (String studentId : studentIds) {
                StudentRowCounts counts = purgeQueryRepository.countForStudent(studentId);
                int objectsForStudent = counts.fileIds().size();

                if (!dryRun) {
                    objectsForStudent = fileService.forcePurge(counts.fileIds());
                    int rowsForStudent = purgeStudentTransaction.deleteStudent(studentId);
                    totalRows += rowsForStudent;
                    studentsPurged++;
                }
                totalObjects += objectsForStudent;

                details.add(new StudentPurgeDetail(studentId, counts.serviceRecords(), counts.accountTransactions(),
                        counts.cases(), counts.enrollments(), counts.proposals(), counts.notifications(), counts.fileIds().size()));
            }

            if (dryRun) {
                purgeRunRepository.complete(runId, studentIds.size(), 0, 0, totalObjects);
            } else {
                purgeRunRepository.complete(runId, studentIds.size(), studentsPurged, totalRows, totalObjects);
            }
        } catch (Exception e) {
            log.error("Purge run {} (cohort {}, dryRun={}) failed after processing {} of {} students",
                    runId, graduationYear, dryRun, studentsPurged, studentIds.size(), e);
            purgeRunRepository.fail(runId, String.valueOf(e.getMessage()));
            throw e;
        }

        log.info("Purge run {} (cohort {}, dryRun={}) complete: {} students scanned, {} purged, {} rows, {} objects",
                runId, graduationYear, dryRun, studentIds.size(), studentsPurged, totalRows, totalObjects);
        return new PurgeRunSummaryDto(runId, dryRun, POLICY_VERSION, studentIds.size(), studentsPurged, totalRows, totalObjects, details);
    }
}
