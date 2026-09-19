package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.application.ExportGradeDerivation.Cohort;
import cn.timebank.dataexchange.infrastructure.ExportScopeRepository;
import cn.timebank.dataexchange.infrastructure.ServiceRecordExportRepository;
import cn.timebank.dataexchange.infrastructure.StudentExportRepository;
import cn.timebank.file.application.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.timebank.dataexchange.application.JobExecutorConfig.JOB_EXECUTOR;

/**
 * B5.9: runs on the dedicated {@code jobExecutor} (D5's executor isolation),
 * never inline in the request that created the job. Writes to a local temp file
 * rather than buffering in memory — bounded heap regardless of export size
 * (Design.md §16.2), the same reasoning {@code LocalFileStorage} (B3.4) already
 * applies to uploads.
 *
 * <p>Formatting is not this class's business: every column convention lives in
 * {@link ExportCsvWriter} (§2.8), and the per-type methods below supply values.
 * The scope and the selected year arrive on the job as JSON, resolved once at
 * request time by {@code ExportScopeResolver} — a different thread cannot
 * re-derive a principal's scope, and re-deriving it later could give a different
 * answer if their assignments changed in between.</p>
 */
@Component
class ExportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ExportJobRunner.class);
    private static final int CHUNK_SIZE = 1000;

    private final JobProgressWriter progressWriter;
    private final ServiceRecordExportRepository serviceRecordExportRepository;
    private final StudentExportRepository studentExportRepository;
    private final ExportScopeRepository scopeRepository;
    private final ExportJobParameters jobParameters;
    private final FileStorage fileStorage;

    ExportJobRunner(JobProgressWriter progressWriter,
                     ServiceRecordExportRepository serviceRecordExportRepository,
                     StudentExportRepository studentExportRepository,
                     ExportScopeRepository scopeRepository,
                     ExportJobParameters jobParameters,
                     FileStorage fileStorage) {
        this.progressWriter = progressWriter;
        this.serviceRecordExportRepository = serviceRecordExportRepository;
        this.studentExportRepository = studentExportRepository;
        this.scopeRepository = scopeRepository;
        this.jobParameters = jobParameters;
        this.fileStorage = fileStorage;
    }

    @Async(JOB_EXECUTOR)
    void runAsync(String jobId) {
        var claimed = progressWriter.claim(jobId, "RUNNING");
        String reportType = claimed.getResourceType();
        Path tempFile;
        try {
            tempFile = Files.createTempFile("export-" + jobId, ".csv");
        } catch (Exception e) {
            progressWriter.fail(jobId, "FAILED", "Could not create export temp file: " + e.getMessage());
            return;
        }
        try {
            ExportScope scope = jobParameters.read(claimed.getParamsJson());
            // The criteria were fixed when the request was authorised; the id set
            // is materialised here, inside the job, so params_json stays a few
            // ids rather than growing with the roster.
            Set<String> studentIds = scope.allStudents() ? null
                    : scopeRepository.studentIdsWithin(
                            scope.restriction().boundGradeIds(), scope.restriction().boundClassIds(),
                            scope.restriction().gradeFilter(), scope.restriction().classFilter());
            Labels labels = loadLabels();

            int rows;
            try (ExportCsvWriter writer = new ExportCsvWriter(tempFile)) {
                rows = switch (reportType) {
                    case "studentList" -> writeStudentList(jobId, writer, scope, studentIds, labels);
                    case "serviceRecords" -> writeServiceRecords(jobId, writer, scope, studentIds, labels);
                    // Phases 3-4 register their own cases here. Until then an
                    // unknown type is a fault rather than an empty file: the
                    // request path already refuses types with no query, so
                    // reaching this means the two disagree.
                    default -> throw new IllegalStateException("No export writer for type: " + reportType);
                };
            }

            String storageKey = "export_" + jobId + ".csv";
            try (var in = new FileInputStream(tempFile.toFile())) {
                fileStorage.store(storageKey, in);
            }
            progressWriter.succeed(jobId, "SUCCEEDED", storageKey, rows, rows);
            log.info("Export job {} ({}) completed: {} rows", jobId, reportType, rows);
        } catch (Exception e) {
            log.error("Export job {} ({}) failed", jobId, reportType, e);
            progressWriter.fail(jobId, "FAILED", String.valueOf(e.getMessage()));
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {
                // best-effort cleanup; the OS temp dir reaps orphans anyway
            }
        }
    }

    /**
     * The label lookups every export shares, read once per job rather than per
     * row. The school has a few grades and a few dozen classes, so these are
     * small maps; joining them per row would be a join per row for data that
     * cannot change while the job runs.
     */
    private record Labels(Map<String, Integer> gradeLevels, Map<String, String> gradeNames,
                           Map<String, String> classCodes, Map<String, Integer> yearStarts) {
    }

    private Labels loadLabels() {
        return new Labels(scopeRepository.gradeLevelsById(),
                scopeRepository.gradeNamesById(false),
                scopeRepository.classCodesById(),
                scopeRepository.academicYearStartYearsById());
    }

    /** §6.1: identity and placement only — deliberately no hours or coins, so nobody reads a roster as an achievement report. */
    private int writeStudentList(String jobId, ExportCsvWriter writer, ExportScope scope,
                                 Set<String> studentIds, Labels labels) throws Exception {
        writer.header("Student Number", "Student Name", "Grade", "Class", "Status",
                "Enrollment Academic Year", "Graduation Year", "Joined At");
        // An empty set means the filters matched nobody, which is a real answer
        // and a header-only file. It must not reach SQL as `in ()`.
        if (studentIds != null && studentIds.isEmpty()) {
            return 0;
        }
        String afterId = null;
        List<StudentExportRepository.ExportRow> chunk;
        do {
            chunk = studentExportRepository.page(afterId, CHUNK_SIZE, studentIds);
            for (StudentExportRepository.ExportRow row : chunk) {
                afterId = row.id();
                GradeCell grade = gradeCell(row.gradeId(), row.enrollmentAcademicYearId(),
                        row.graduationYear(), scope, labels);
                if (grade.excluded()) {
                    continue; // §5.1: not enrolled that year ⇒ absent, not blank
                }
                writer.row(row.studentNumber(), row.displayName(), grade.label(),
                        labels.classCodes().get(row.classId()), statusLabel(row.status()),
                        academicYearCode(row.enrollmentAcademicYearId(), labels),
                        row.graduationYear(), row.joinedAt());
            }
            progressWriter.renewLeaseAndProgress(jobId, writer.rowsWritten(), writer.rowsWritten());
        } while (chunk.size() == CHUNK_SIZE);
        return writer.rowsWritten();
    }

    /** §6.2: every submitted status, so `Credited Coins` is empty on rows that have not been approved. */
    private int writeServiceRecords(String jobId, ExportCsvWriter writer, ExportScope scope,
                                    Set<String> studentIds, Labels labels) throws Exception {
        writer.header("Student Number", "Student Name", "Grade", "Class", "Service Date",
                "Service Category", "Project / Organisation", "Duration Minutes",
                "Credited Coins", "Status");
        if (studentIds != null && studentIds.isEmpty()) {
            return 0;
        }
        String afterId = null;
        List<ServiceRecordExportRepository.ExportRow> chunk;
        do {
            chunk = serviceRecordExportRepository.page(afterId, CHUNK_SIZE, studentIds,
                    scope.selectedYearIndex());
            for (ServiceRecordExportRepository.ExportRow row : chunk) {
                afterId = row.id();
                writer.row(row.studentNumber(), row.studentName(),
                        // The record's own grade snapshot is the truthful answer
                        // for a record: it is the grade the student was in when
                        // they did the service. Unlike the roster export there is
                        // nothing to derive.
                        labels.gradeNames().get(row.gradeId()),
                        labels.classCodes().get(row.classId()),
                        row.serviceDate(), row.serviceCategory(), row.subject(),
                        row.durationMinutes(),
                        ExportCsvWriter.coins(creditedOnlyWhenApproved(row)),
                        recordStatusLabel(row.status()));
            }
            progressWriter.renewLeaseAndProgress(jobId, writer.rowsWritten(), writer.rowsWritten());
        } while (chunk.size() == CHUNK_SIZE);
        return writer.rowsWritten();
    }

    /**
     * `Credited Coins` is populated only on approved rows (§6.2).
     *
     * <p>Belt and braces over the query: a non-approved record should not carry
     * credited units at all, but if one ever does — a reversal mid-flight, a
     * historical row — printing it here would put a credit figure beside a status
     * that says it was rejected, and someone would sum the column.</p>
     */
    private Integer creditedOnlyWhenApproved(ServiceRecordExportRepository.ExportRow row) {
        return "APPROVED".equals(row.status()) ? row.creditedUnits() : null;
    }

    /** What the Grade column says for one student, and whether they belong in this year's file at all. */
    private record GradeCell(String label, boolean excluded) {
    }

    private GradeCell gradeCell(String liveGradeId, String enrolmentYearId, Integer graduationYear,
                                 ExportScope scope, Labels labels) {
        String liveLabel = labels.gradeNames().get(liveGradeId);
        Integer liveLevel = labels.gradeLevels().get(liveGradeId);
        Integer enrolmentYear = enrolmentYearId == null ? null : labels.yearStarts().get(enrolmentYearId);

        // §5.3: with no year selected there is nothing to derive against, so the
        // column shows present state — including `Graduated (YYYY)`.
        if (!scope.hasYear()) {
            if (graduationYear != null && scope.currentYearIndex() >= graduationYear) {
                return new GradeCell("Graduated (" + graduationYear + ")", false);
            }
            return new GradeCell(liveLabel, false);
        }

        // Without a cohort there is nothing to derive from. Showing the live grade
        // for a past year would be a confident wrong answer, so the row is kept
        // with an empty grade rather than asserting one.
        if (enrolmentYear == null || graduationYear == null || liveLevel == null) {
            return new GradeCell(null, false);
        }

        Cohort cohort = new Cohort(enrolmentYear, graduationYear, liveLevel);
        if (!ExportGradeDerivation.enrolledIn(cohort, scope.selectedYearIndex())) {
            return new GradeCell(null, true);
        }
        if (ExportGradeDerivation.isInconsistent(cohort, scope.currentYearIndex())) {
            // §5.2.1: this student repeated or skipped a year, which the system
            // does not model. An empty cell is honest; a derived number would not be.
            return new GradeCell(null, false);
        }
        int level = ExportGradeDerivation.gradeLevelIn(cohort, scope.selectedYearIndex(), scope.currentYearIndex());
        return new GradeCell(gradeLabelForLevel(level, labels), false);
    }

    /** Renders a derived level through the same names the live grades use, so a derived row and a present row read alike. */
    private String gradeLabelForLevel(int level, Labels labels) {
        for (Map.Entry<String, Integer> entry : labels.gradeLevels().entrySet()) {
            if (entry.getValue() == level) {
                String name = labels.gradeNames().get(entry.getKey());
                if (name != null) {
                    return name;
                }
            }
        }
        return "Grade " + level;
    }

    private String academicYearCode(String academicYearId, Labels labels) {
        Integer start = academicYearId == null ? null : labels.yearStarts().get(academicYearId);
        return start == null ? null : start + "-" + (start + 1);
    }

    /** §2.5: statuses go through a label map, never {@code enum.name()} — `PENDING_ACTIVATION` is not a sentence. */
    private static String statusLabel(String status) {
        return switch (status) {
            case "ACTIVE" -> "Active";
            case "INACTIVE" -> "Inactive";
            case "GRADUATED" -> "Graduated";
            case null -> null;
            default -> status;
        };
    }

    /** §6.2's four labels over five system statuses: the two review-in-progress states read the same to a reader. */
    private static String recordStatusLabel(String status) {
        return switch (status) {
            case "APPROVED" -> "Approved";
            case "SUBMITTED", "UNDER_REVIEW" -> "Pending Review";
            case "RETURNED" -> "Returned";
            case "REJECTED" -> "Rejected";
            case null -> null;
            default -> status;
        };
    }
}
