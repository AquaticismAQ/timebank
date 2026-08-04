package cn.timebank.dataexchange.application;

import cn.timebank.dataexchange.infrastructure.ServiceRecordExportRepository;
import cn.timebank.dataexchange.infrastructure.ServiceRecordExportRepository.ExportRow;
import cn.timebank.file.application.FileStorage;
import cn.timebank.reporting.application.ReportService;
import cn.timebank.reporting.application.dto.CompletionReportDto;
import cn.timebank.reporting.application.dto.ParticipationReportDto;
import cn.timebank.reporting.application.dto.ReviewEfficiencyReportDto;
import cn.timebank.reporting.application.dto.ServiceHoursReportDto;
import cn.timebank.reporting.application.dto.TopProjectsReportDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static cn.timebank.dataexchange.application.JobExecutorConfig.JOB_EXECUTOR;

/**
 * B5.9: runs on the dedicated {@code jobExecutor} (D5's executor
 * isolation), never inline in the request that created the job. Writes to
 * a local temp file rather than buffering in memory — bounded heap
 * regardless of export size (Design.md §16.2), the same reasoning
 * `LocalFileStorage` (B3.4) already applies to uploads.
 */
@Component
class ExportJobRunner {

    private static final Logger log = LoggerFactory.getLogger(ExportJobRunner.class);
    private static final int CHUNK_SIZE = 1000;

    private final JobProgressWriter progressWriter;
    private final ReportService reportService;
    private final ServiceRecordExportRepository serviceRecordExportRepository;
    private final FileStorage fileStorage;

    ExportJobRunner(JobProgressWriter progressWriter, ReportService reportService,
                     ServiceRecordExportRepository serviceRecordExportRepository, FileStorage fileStorage) {
        this.progressWriter = progressWriter;
        this.reportService = reportService;
        this.serviceRecordExportRepository = serviceRecordExportRepository;
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
            int rows = "serviceRecords".equals(reportType)
                    ? writeServiceRecordsCsv(jobId, tempFile)
                    : writeReportCsv(reportType, tempFile);

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

    /** The one export type large enough to actually need chunking/checkpointing — keyset-paginated, ~1,000 rows per chunk, lease renewed each chunk. */
    private int writeServiceRecordsCsv(String jobId, Path tempFile) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
            writer.write("id,studentId,studentName,serviceDate,durationMinutes,creditedUnits,status");
            writer.newLine();
            String afterId = null;
            int total = 0;
            List<ExportRow> chunk;
            do {
                chunk = serviceRecordExportRepository.page(afterId, CHUNK_SIZE);
                for (ExportRow row : chunk) {
                    writer.write(csvLine(row.id(), row.studentId(), row.studentName(), String.valueOf(row.serviceDate()),
                            String.valueOf(row.durationMinutes()), String.valueOf(row.creditedUnits()), row.status()));
                    writer.newLine();
                    afterId = row.id();
                }
                total += chunk.size();
                progressWriter.renewLeaseAndProgress(jobId, total, total);
            } while (chunk.size() == CHUNK_SIZE);
            return total;
        }
    }

    /** The five aggregate reports are already bounded (grades, months, reviewers, top-N projects) — one small write, no chunking needed, still off the request thread. */
    private int writeReportCsv(String reportType, Path tempFile) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(tempFile)) {
            int rows = switch (reportType) {
                case "participation" -> writeParticipation(writer);
                case "completion" -> writeCompletion(writer);
                case "serviceHours" -> writeServiceHours(writer);
                case "reviewEfficiency" -> writeReviewEfficiency(writer);
                case "topProjects" -> writeTopProjects(writer);
                default -> throw new IllegalStateException("Unknown report export type: " + reportType);
            };
            return rows;
        }
    }

    private int writeParticipation(BufferedWriter writer) throws Exception {
        ParticipationReportDto report = reportService.participation("zh-CN");
        writer.write("gradeId,gradeName,totalStudents,participatedStudents,participationRate");
        writer.newLine();
        for (var g : report.byGrade()) {
            writer.write(csvLine(g.gradeId(), g.gradeName(), String.valueOf(g.totalStudents()),
                    String.valueOf(g.participatedStudents()), String.valueOf(g.participationRate())));
            writer.newLine();
        }
        return report.byGrade().size();
    }

    private int writeCompletion(BufferedWriter writer) throws Exception {
        CompletionReportDto report = reportService.completion("zh-CN");
        writer.write("gradeId,gradeName,totalStudents,completedStudents,completionRate");
        writer.newLine();
        for (var g : report.byGrade()) {
            writer.write(csvLine(g.gradeId(), g.gradeName(), String.valueOf(g.totalStudents()),
                    String.valueOf(g.completedStudents()), String.valueOf(g.completionRate())));
            writer.newLine();
        }
        return report.byGrade().size();
    }

    private int writeServiceHours(BufferedWriter writer) throws Exception {
        ServiceHoursReportDto report = reportService.serviceHours();
        writer.write("month,hours");
        writer.newLine();
        for (var m : report.byMonth()) {
            writer.write(csvLine(m.month(), String.valueOf(m.hours())));
            writer.newLine();
        }
        return report.byMonth().size();
    }

    private int writeReviewEfficiency(BufferedWriter writer) throws Exception {
        ReviewEfficiencyReportDto report = reportService.reviewEfficiency();
        writer.write("reviewerId,reviewerName,decisions,avgHours");
        writer.newLine();
        for (var r : report.byReviewer()) {
            writer.write(csvLine(r.reviewerId(), r.reviewerName(), String.valueOf(r.decisions()), String.valueOf(r.avgHours())));
            writer.newLine();
        }
        return report.byReviewer().size();
    }

    private int writeTopProjects(BufferedWriter writer) throws Exception {
        TopProjectsReportDto report = reportService.topProjects();
        writer.write("projectId,title,participants,totalMinutes");
        writer.newLine();
        for (var p : report.topProjects()) {
            writer.write(csvLine(p.projectId(), p.title(), String.valueOf(p.participants()), String.valueOf(p.totalMinutes())));
            writer.newLine();
        }
        return report.topProjects().size();
    }

    private String csvLine(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String f = fields[i] == null ? "" : fields[i];
            if (f.contains(",") || f.contains("\"")) {
                sb.append('"').append(f.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(f);
            }
        }
        return sb.toString();
    }
}
