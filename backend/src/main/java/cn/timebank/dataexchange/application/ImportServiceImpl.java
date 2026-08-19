package cn.timebank.dataexchange.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.idempotency.IdempotencyService;
import cn.timebank.dataexchange.application.dto.ImportErrorReportDto;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.dataexchange.domain.Job;
import cn.timebank.dataexchange.domain.JobKind;
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

    static final Set<String> SUPPORTED_TYPES = Set.of("serviceRecordsHistorical", "students");
    private static final Set<String> RESUMABLE_COMMIT_STATUSES = Set.of("VALIDATED", "COMMIT_FAILED");

    private final JobRepository jobRepository;
    private final ImportJobRunner importJobRunner;
    private final FileStorage fileStorage;
    private final IdempotencyService idempotencyService;

    ImportServiceImpl(JobRepository jobRepository, ImportJobRunner importJobRunner, FileStorage fileStorage,
                       IdempotencyService idempotencyService) {
        this.jobRepository = jobRepository;
        this.importJobRunner = importJobRunner;
        this.fileStorage = fileStorage;
        this.idempotencyService = idempotencyService;
    }

    @Override
    public String template(String type) {
        if (!SUPPORTED_TYPES.contains(type)) {
            throw ApiException.notFound("Unknown import type: " + type);
        }
        if ("students".equals(type)) {
            return "studentNumber,displayName,gradeId,classId,enrollmentAcademicYearId,graduationYear,joinedAt,email,guardianName,guardianContact\n"
                    + "stu_1001,Zhang San,grade_6,class_6A,ay_2025_2026,2032,2026-09-01,,,\n";
        }
        return "studentNumber,serviceDate,durationMinutes,description\n"
                + "stu_0001,2024-10-01,120,Community cleanup (paper ledger 2024 fall)\n";
    }

    @Override
    @Transactional
    public JobDto create(String type, MultipartFile file, String actorId) {
        if (!SUPPORTED_TYPES.contains(type)) {
            throw ApiException.invalidRequest("Unknown import type: " + type);
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.invalidRequest("An import file is required.");
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
        return toDto(job);
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
        importJobRunner.commitAsync(jobId, actorId);
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
