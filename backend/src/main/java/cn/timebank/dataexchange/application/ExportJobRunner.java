package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.application.ExportGradeDerivation.Cohort;
import cn.timebank.dataexchange.infrastructure.ExportScopeRepository;
import cn.timebank.dataexchange.infrastructure.ServiceRecordExportRepository;
import cn.timebank.dataexchange.infrastructure.StudentExportRepository;
import cn.timebank.dataexchange.infrastructure.ProjectReviewExportRepository;
import cn.timebank.dataexchange.infrastructure.StudentReportExportRepository;
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
    private final StudentReportExportRepository studentReportRepository;
    private final ProjectReviewExportRepository projectReviewRepository;
    private final ExportScopeRepository scopeRepository;
    private final ExportJobParameters jobParameters;
    private final FileStorage fileStorage;

    ExportJobRunner(JobProgressWriter progressWriter,
                     ServiceRecordExportRepository serviceRecordExportRepository,
                     StudentExportRepository studentExportRepository,
                     StudentReportExportRepository studentReportRepository,
                     ProjectReviewExportRepository projectReviewRepository,
                     ExportScopeRepository scopeRepository,
                     ExportJobParameters jobParameters,
                     FileStorage fileStorage) {
        this.progressWriter = progressWriter;
        this.serviceRecordExportRepository = serviceRecordExportRepository;
        this.studentExportRepository = studentExportRepository;
        this.studentReportRepository = studentReportRepository;
        this.projectReviewRepository = projectReviewRepository;
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
                    case "studentCompletion" -> writeStudentCompletion(jobId, writer, scope, studentIds, labels);
                    case "studentParticipation" -> writeStudentParticipation(jobId, writer, scope, studentIds, labels);
                    case "projects" -> writeProjects(jobId, writer, scope, studentIds, labels);
                    case "reviewEfficiency" -> writeReviewEfficiency(writer, scope, studentIds);
                    case "reviewHistory" -> writeReviewHistory(jobId, writer, scope, studentIds);
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
                    scope.yearFrom(), scope.yearTo());
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
     * §6.3: one row per student for a <b>single</b> academic year.
     *
     * <p>The year matters more here than anywhere else, because the requirement
     * being measured against is an annual one. With no year selected the
     * denominator would be one year's requirement against several years of
     * service, which is not a completion rate at all — so the current year is
     * used when none is given (§4.2, the one documented exception to
     * "everything defaults to All").</p>
     */
    private int writeStudentCompletion(String jobId, ExportCsvWriter writer, ExportScope scope,
                                       Set<String> studentIds, Labels labels) throws Exception {
        writer.header("Student Number", "Student Name", "Grade", "Class",
                "Projects Participated", "Approved Service Hours", "Credited Coins",
                "Annual Required Hours", "Gap Hours", "Completion Rate");
        if (studentIds != null && studentIds.isEmpty()) {
            return 0;
        }
        int yearStart = scope.hasYear() ? scope.selectedYearIndex() : scope.currentYearIndex();
        // §4.2: this report is always one year. When the operator picked one we
        // have its stored window; when they did not, the current year's window is
        // read here rather than computed, for the same reason.
        var window = scope.hasYearWindow()
                ? new java.time.LocalDate[]{scope.yearFrom(), scope.yearTo()}
                : scopeRepository.academicYearWindowFor(yearStart)
                        .map(w -> new java.time.LocalDate[]{w.start(), w.end()})
                        .orElse(new java.time.LocalDate[]{null, null});
        java.time.LocalDate completionFrom = window[0];
        java.time.LocalDate completionTo = window[1];
        var required = studentReportRepository.requiredMinutesFor(completionTo).orElse(null);

        String afterId = null;
        List<StudentReportExportRepository.CompletionRow> chunk;
        do {
            chunk = studentReportRepository.completionPage(afterId, CHUNK_SIZE, studentIds,
                    completionFrom, completionTo);
            for (var row : chunk) {
                afterId = row.studentId();
                ExportScope yearScope = new ExportScope(scope.restriction(), yearStart,
                        scope.currentYearIndex(), scope.reviewerId(), scope.yearFrom(), scope.yearTo());
                GradeCell grade = gradeCell(row.gradeId(), row.enrollmentAcademicYearId(),
                        row.graduationYear(), yearScope, labels);
                if (grade.excluded()) {
                    continue;
                }
                Integer requiredMinutes = requiredMinutesForStudent(required, row.gradeId(), labels);
                writer.row(row.studentNumber(), row.studentName(), grade.label(),
                        labels.classCodes().get(row.classId()),
                        row.projectsParticipated(),
                        ExportCsvWriter.hours((int) row.approvedMinutes()),
                        ExportCsvWriter.coins((int) row.creditedUnits()),
                        ExportCsvWriter.hours(requiredMinutes),
                        // The actionable column (§6.3): "six hours short" is more
                        // use to a form tutor than "67% complete", and it never
                        // goes negative — overachieving is not a negative gap.
                        requiredMinutes == null ? null
                                : ExportCsvWriter.hours((int) Math.max(0, requiredMinutes - row.approvedMinutes())),
                        requiredMinutes == null ? null
                                : ExportCsvWriter.percentage(row.approvedMinutes(), requiredMinutes));
            }
            progressWriter.renewLeaseAndProgress(jobId, writer.rowsWritten(), writer.rowsWritten());
        } while (chunk.size() == CHUNK_SIZE);
        return writer.rowsWritten();
    }

    /**
     * §6.5: registrations beside actual participation.
     *
     * <p>The gap between the two counts is the reason the file exists — a student
     * with four registrations and one participation signed up and did not
     * deliver, which is visible here and nowhere else. "Participated" means at
     * least one approved record for that project, not an approved registration.</p>
     */
    private int writeStudentParticipation(String jobId, ExportCsvWriter writer, ExportScope scope,
                                          Set<String> studentIds, Labels labels) throws Exception {
        writer.header("Student Number", "Student Name", "Grade", "Class",
                "Registered Projects", "Registered Project Names",
                "Participated Projects", "Participated Project Names",
                "Approved Service Hours", "Credited Coins");
        if (studentIds != null && studentIds.isEmpty()) {
            return 0;
        }
        String afterId = null;
        List<StudentReportExportRepository.ParticipationRow> chunk;
        do {
            chunk = studentReportRepository.participationPage(afterId, CHUNK_SIZE, studentIds,
                    scope.yearFrom(), scope.yearTo());
            for (var row : chunk) {
                afterId = row.studentId();
                GradeCell grade = gradeCell(row.gradeId(), row.enrollmentAcademicYearId(),
                        row.graduationYear(), scope, labels);
                if (grade.excluded()) {
                    continue;
                }
                writer.row(row.studentNumber(), row.studentName(), grade.label(),
                        labels.classCodes().get(row.classId()),
                        row.registeredProjects(), row.registeredProjectNames(),
                        row.participatedProjects(), row.participatedProjectNames(),
                        ExportCsvWriter.hours((int) row.approvedMinutes()),
                        ExportCsvWriter.coins((int) row.creditedUnits()));
            }
            progressWriter.renewLeaseAndProgress(jobId, writer.rowsWritten(), writer.rowsWritten());
        } while (chunk.size() == CHUNK_SIZE);
        return writer.rowsWritten();
    }

    /** §6.4: one row per project, with what was actually delivered against the places it offered. */
    private int writeProjects(String jobId, ExportCsvWriter writer, ExportScope scope,
                              Set<String> studentIds, Labels labels) throws Exception {
        writer.header("Project Name", "Project Summary", "Service Category", "Partner Organisation",
                "Location", "Owner", "Eligible Grades", "Status", "Start Date", "End Date",
                "Recognized Minutes", "Capacity", "Registrations", "Participants",
                "Participation Rate", "Approved Service Hours", "Approval Rate");
        String gradeFilter = scope.restriction() == null ? null : scope.restriction().gradeFilter();
        String afterId = null;
        List<ProjectReviewExportRepository.ProjectRow> chunk;
        do {
            chunk = projectReviewRepository.projectsPage(afterId, CHUNK_SIZE, studentIds,
                    scope.yearFrom(), scope.yearTo(), gradeFilter);
            for (var row : chunk) {
                afterId = row.id();
                writer.row(row.title(), row.summary(), row.category(), row.organization(),
                        row.location(), row.owner(), eligibleGradeLabels(row.eligibleGradeIds(), labels),
                        projectStatusLabel(row.status()), row.startAt(), row.endAt(),
                        row.recognizedMinutes(), row.capacity(),
                        row.registrations(), row.participants(),
                        ExportCsvWriter.percentage(row.participants(),
                                row.capacity() == null ? 0 : row.capacity()),
                        ExportCsvWriter.hours((int) row.approvedMinutes()),
                        // Approved over *submitted records*, so it measures how well
                        // service on this project was documented rather than how many
                        // students turned up — a low rate usually means evidence
                        // problems, not absence (§6.4).
                        ExportCsvWriter.percentage(row.approvedRecords(), row.submittedRecords()));
            }
            progressWriter.renewLeaseAndProgress(jobId, writer.rowsWritten(), writer.rowsWritten());
        } while (chunk.size() == CHUNK_SIZE);
        return writer.rowsWritten();
    }

    /**
     * §6.6: one row per reviewer, counts beside rates.
     *
     * <p>Bounded by the number of reviewers, so no chunking — the same reasoning
     * the old aggregate reports used, and still true.</p>
     */
    private int writeReviewEfficiency(ExportCsvWriter writer, ExportScope scope, Set<String> studentIds)
            throws Exception {
        writer.header("Reviewer Name", "Reviewer Role", "Bound Grades", "Total Reviews",
                "Approved", "Rejected", "Returned", "Average Review Hours",
                "Approval Rate", "Rejection Rate", "Return Rate");
        if (studentIds != null && studentIds.isEmpty()) {
            return 0;
        }
        for (var row : projectReviewRepository.reviewerEfficiency(scope.yearFrom(), scope.yearTo(), studentIds)) {
            writer.row(row.reviewerName(), roleLabel(row.reviewerRole()), row.boundGrades(),
                    row.total(), row.approved(), row.rejected(), row.returned(),
                    row.averageReviewMinutes() == null ? null
                            : ExportCsvWriter.hours((int) Math.round(row.averageReviewMinutes())),
                    // Over the total, which includes escalations (Q-3) — so these
                    // three do not sum to 100% for a reviewer who escalated. Read
                    // the counts first: one review approved is also 100.00%.
                    ExportCsvWriter.percentage(row.approved(), row.total()),
                    ExportCsvWriter.percentage(row.rejected(), row.total()),
                    ExportCsvWriter.percentage(row.returned(), row.total()));
        }
        return writer.rowsWritten();
    }

    /** §6.7: one row per review decision, including the reason and comment given on returns and rejections. */
    private int writeReviewHistory(String jobId, ExportCsvWriter writer, ExportScope scope,
                                   Set<String> studentIds) throws Exception {
        writer.header("Reviewer Name", "Reviewer Role", "Bound Grades", "Student Number", "Student Name",
                "Service Date", "Project / Organisation", "Decision", "Decided At",
                "Reason Code", "Comment", "Duration Minutes", "Credited Coins");
        if (studentIds != null && studentIds.isEmpty()) {
            return 0;
        }
        String afterId = null;
        List<ProjectReviewExportRepository.ReviewHistoryRow> chunk;
        do {
            chunk = projectReviewRepository.reviewHistoryPage(afterId, CHUNK_SIZE,
                    scope.yearFrom(), scope.yearTo(), scope.reviewerId(), studentIds);
            for (var row : chunk) {
                afterId = row.id();
                writer.row(row.reviewerName(), roleLabel(row.reviewerRole()), row.boundGrades(),
                        row.studentNumber(), row.studentName(), row.serviceDate(), row.subject(),
                        decisionLabel(row.decision()), row.decidedAt(),
                        row.reasonCode(), row.comment(), row.durationMinutes(),
                        ExportCsvWriter.coins("APPROVED".equals(row.recordStatus())
                                ? row.creditedUnits() : null));
            }
            progressWriter.renewLeaseAndProgress(jobId, writer.rowsWritten(), writer.rowsWritten());
        } while (chunk.size() == CHUNK_SIZE);
        return writer.rowsWritten();
    }

    /** Grade ids as readable names. The column is for a human, so `grade_10` is not an answer. */
    private String eligibleGradeLabels(String eligibleGradeIdsJson, Labels labels) {
        if (eligibleGradeIdsJson == null || eligibleGradeIdsJson.isBlank()) {
            // Q-7: no eligible grades means open to all, which is a fact worth
            // stating rather than an empty cell a reader would read as missing data.
            return "All grades";
        }
        List<String> names = new java.util.ArrayList<>();
        for (String raw : eligibleGradeIdsJson.replaceAll("[\\[\\]\"]", "").split(",")) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                names.add(labels.gradeNames().getOrDefault(id, id));
            }
        }
        return String.join(", ", names);
    }

    /** §2.5 again: a role code is not a label, and `RETURN_FOR_CHANGES` is not a sentence. */
    private static String roleLabel(String roleCode) {
        return switch (roleCode) {
            case "admin" -> "SDC Admin";
            case "sysadmin" -> "System Admin";
            case "teacher" -> "Teacher";
            case "student" -> "Student";
            case null -> null;
            default -> roleCode;
        };
    }

    private static String decisionLabel(String decision) {
        return switch (decision) {
            case "APPROVE" -> "Approved";
            case "REJECT" -> "Rejected";
            case "RETURN_FOR_CHANGES" -> "Returned";
            // Q-3's fourth label. Omitting escalations would hide real decisions
            // from a file called Review History.
            case "ESCALATE" -> "Escalated";
            case null -> null;
            default -> decision;
        };
    }

    private static String projectStatusLabel(String status) {
        return switch (status) {
            case "DRAFT" -> "Draft";
            case "PUBLISHED" -> "Published";
            case "REGISTRATION_OPEN" -> "Registration Open";
            case "REGISTRATION_CLOSED" -> "Registration Closed";
            case "IN_PROGRESS" -> "In Progress";
            case "COMPLETED" -> "Completed";
            case "CANCELLED" -> "Cancelled";
            case null -> null;
            default -> status;
        };
    }

    /**
     * Which band's annual requirement applies to this student (F-21).
     *
     * <p>Grades 6-8 are the junior band and 9-12 the senior one, matching the
     * two-stage school model. Returns null when no published rule covers the
     * year, which leaves the requirement, gap and rate columns empty rather than
     * inventing a denominator — a made-up requirement would make every
     * completion rate in the file wrong in a way nobody could see.</p>
     */
    private Integer requiredMinutesForStudent(StudentReportExportRepository.RequiredMinutes required,
                                               String gradeId, Labels labels) {
        if (required == null) {
            return null;
        }
        Integer level = labels.gradeLevels().get(gradeId);
        if (level == null) {
            return null;
        }
        return level >= 9 ? required.highAnnualMinutes() : required.middleAnnualMinutes();
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
