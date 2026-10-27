package cn.timebank.dataexchange.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.idempotency.IdempotencyService;
import cn.timebank.common.web.RequestContext;
import cn.timebank.dataexchange.application.dto.ImportErrorReportDto;
import cn.timebank.dataexchange.application.dto.ImportTemplateDto;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.dataexchange.domain.Job;
import cn.timebank.dataexchange.domain.JobKind;
import cn.timebank.dataexchange.application.validation.ImportRuleSet;
import cn.timebank.dataexchange.application.validation.RuleSetSource;
import cn.timebank.dataexchange.infrastructure.JobRepository;
import cn.timebank.file.application.FileStorage;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.TimeBankPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
class ImportServiceImpl implements ImportService {

    private static final Set<String> RESUMABLE_COMMIT_STATUSES = Set.of("VALIDATED", "COMMIT_FAILED");

    private final JobRepository jobRepository;
    private final ImportJobRunner importJobRunner;
    private final FileStorage fileStorage;
    private final IdempotencyService idempotencyService;
    private final RuleSetSource ruleSetSource;
    private final HistoricalImportWindowService windowService;
    private final AuditService auditService;

    ImportServiceImpl(JobRepository jobRepository, ImportJobRunner importJobRunner, FileStorage fileStorage,
                       IdempotencyService idempotencyService, RuleSetSource ruleSetSource,
                       HistoricalImportWindowService windowService, AuditService auditService) {
        this.jobRepository = jobRepository;
        this.importJobRunner = importJobRunner;
        this.fileStorage = fileStorage;
        this.idempotencyService = idempotencyService;
        this.ruleSetSource = ruleSetSource;
        this.windowService = windowService;
        this.auditService = auditService;
    }

    /**
     * The rule set is the single authority for which import types exist — the
     * API accepts exactly what `import-rules.yaml` declares, so adding a type
     * cannot leave the endpoint and the validator disagreeing.
     */
    private boolean isSupported(String type) {
        return ruleSetSource.supportedTypes().contains(type);
    }

    /**
     * Generated from the rule set rather than written out by hand (plan §3.5).
     * The template and the validation now cannot drift apart: a column added to
     * `import-rules.yaml` appears here automatically, and one removed disappears
     * from both at once. Previously the two were authored separately, so a
     * template could advertise a column the validator did not read.
     */
    @Override
    public ImportTemplateDto template(String type) {
        if (!isSupported(type)) {
            throw ApiException.notFound("Unknown import type: " + type);
        }
        ImportRuleSet ruleSet = ruleSetSource.forType(type);
        String header = String.join(",", ruleSet.columnNames());
        String sample = ruleSet.columns().stream()
                .map(ImportRuleSet.ColumnSpec::sample)
                .map(ImportServiceImpl::csvCell)
                .collect(java.util.stream.Collectors.joining(","));
        // The rule set's own name, not the path variable: validated above, and the
        // only value that reaches the Content-Disposition header.
        return new ImportTemplateDto(ruleSet.type() + "-template.csv", header + "\n" + sample + "\n");
    }

    /** Quote a sample value the same way the exporter would, so the template round-trips through the reader. */
    private static String csvCell(String value) {
        return value.contains(",") || value.contains("\"")
                ? '"' + value.replace("\"", "\"\"") + '"'
                : value;
    }

    @Override
    @Transactional
    public JobDto create(String type, MultipartFile file, String actorId) {
        if (!isSupported(type)) {
            throw ApiException.invalidRequest("Unknown import type: " + type);
        }
        // First of §2.9's two enforcement points, and before the file is read:
        // once the 15-day window has closed there is no reason to spend a disk
        // write and a SHA-256 pass on a file that can never be committed
        // (12-DataImport.md §6.1). Roster import never reaches this check.
        windowService.requireImportable(type);
        if (file == null || file.isEmpty()) {
            throw ApiException.invalidRequest("An import file is required.");
        }
        // File-level cap (plan §3.4, gap G-6), rejected before a byte is parsed
        // so an oversized upload cannot exhaust memory on the way to finding out
        // it was too big.
        ImportRuleSet ruleSet = ruleSetSource.forType(type);
        if (file.getSize() > ruleSet.maxBytes()) {
            throw ApiException.invalidRequest(
                    "Import file is larger than the " + (ruleSet.maxBytes() / (1024 * 1024)) + " MiB limit for this type.");
        }

        String jobId = OpaqueIds.generate("job");
        String storageKey = "import_" + jobId + ".csv";
        String hash;
        try {
            Path tempFile = Files.createTempFile("import-" + jobId, ".csv");
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = file.getInputStream();
                     DigestInputStream digestIn = new DigestInputStream(in, digest)) {
                    Files.copy(digestIn, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                hash = HexFormat.of().formatHex(digest.digest());
                try (InputStream toStore = Files.newInputStream(tempFile)) {
                    fileStorage.store(storageKey, toStore);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new java.io.UncheckedIOException("Could not read import file", new IOException(e));
        }

        if (jobRepository.findBySourceFileHashAndStatus(hash, "COMMITTED").isPresent()) {
            throw ApiException.stateConflict("This exact file has already been imported.");
        }

        Job job = new Job(jobId, JobKind.IMPORT, type, "QUEUED", actorId, Instant.now());
        job.setSourceFileKey(storageKey);
        job.setSourceFileHash(hash);
        jobRepository.saveAndFlush(job);
        // Issue 149. The upload, not only the commit: since issue 146 a file
        // with one bad row is a complete no-op, so "somebody uploaded a roster
        // of two hundred real children and it bounced" would otherwise leave
        // nothing at all behind. Same split as export.create — the request is
        // the decision a person made, whatever became of it.
        auditService.record(new AuditEntry(actorId, "imports.create",
                "import.create", "import", jobId, null,
                new ImportUploaded(type, hash, file.getSize()), null));
        return toDto(job);
    }

    /** The audit row's `after` summary: which file, identified by content rather than by name. */
    private record ImportUploaded(String type, String sha256, long bytes) {
    }

    @Override
    @Transactional(readOnly = true)
    public JobDto get(String jobId, Authentication auth) {
        return toDto(findAccessibleOrThrow(jobId, auth));
    }

    @Override
    @Transactional
    public JobDto validate(String jobId, String actorId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Import not found."));
        if (!Set.of("QUEUED", "VALIDATION_FAILED").contains(job.getStatus())) {
            throw ApiException.stateConflict("Import is not in a state that can be (re)validated.");
        }
        importJobRunner.validateAsync(jobId);
        return toDto(job);
    }

    @Override
    @Transactional(readOnly = true)
    public ImportErrorReportDto errors(String jobId, Authentication auth) {
        Job job = findAccessibleOrThrow(jobId, auth);
        if (job.getErrorRef() == null) {
            return new ImportErrorReportDto(0, List.of());
        }
        List<ImportErrorReportDto.RowError> rows = new ArrayList<>();
        try (InputStream in = fileStorage.open(job.getErrorRef());
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.readLine(); // header
            String line;
            while ((line = reader.readLine()) != null && rows.size() < 1000) {
                String[] p = line.split(",", 4);
                if (p.length == 4) {
                    rows.add(new ImportErrorReportDto.RowError(Integer.parseInt(p[0]), p[1], p[2], p[3]));
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new ImportErrorReportDto(rows.size(), rows);
    }

    @Override
    public JobDto commit(String jobId, String actorId, String idempotencyKey) {
        return idempotencyService.execute(idempotencyKey, actorId, "POST /imports/" + jobId + "/commit",
                Map.of("id", jobId), JobDto.class, () -> doCommit(jobId, actorId));
    }

    @Transactional
    JobDto doCommit(String jobId, String actorId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Import not found."));
        if (!RESUMABLE_COMMIT_STATUSES.contains(job.getStatus())) {
            throw ApiException.stateConflict("Import must be validated before it can be committed.");
        }
        // Second enforcement point (Q-8). A job validated twenty minutes before
        // the window closed must not commit after it: honouring in-flight jobs
        // would make the expiry advisory, and "validate now, commit whenever"
        // is precisely the indefinite door §6 closes.
        windowService.requireCommittable(job.getResourceType());
        // Snapshotted here, on the request thread, because the commit runs on a
        // job-executor thread where RequestContext is empty and every audit row
        // it writes would otherwise say `no-request` with no IP (issue 149).
        importJobRunner.commitAsync(jobId, actorId, RequestContext.snapshot());
        return toDto(job);
    }

    @Override
    @Transactional
    public JobDto cancel(String jobId, String actorId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Import not found."));
        if (Set.of("COMMITTED", "COMMITTING", "CANCELLED").contains(job.getStatus())) {
            throw ApiException.stateConflict("Import can no longer be cancelled.");
        }
        job.setStatus("CANCELLED");
        job.setFinishedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
        return toDto(job);
    }

    private Job findAccessibleOrThrow(String jobId, Authentication auth) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Import not found."));
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean hasFullScope = principal.hasFullDataScope(); // 07-RoleFinetune.md §2.1
        if (!hasFullScope && !job.getCreatedBy().equals(principal.id())) {
            throw ApiException.notFound("Import not found.");
        }
        return job;
    }

    private JobDto toDto(Job job) {
        return new JobDto(job.getId(), job.getKind().name(), job.getResourceType(), job.getStatus(), job.getCreatedBy(),
                job.getTotalRows(), job.getValidRows(), job.getProcessedRows(), job.getCheckpointOffset(),
                job.getErrorMessage(), job.getVersion(), job.getCreatedAt(), job.getUpdatedAt(), job.getFinishedAt());
    }
}
