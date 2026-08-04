package cn.timebank.dataexchange.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.dataexchange.application.dto.DataExchangeRequests.CreateExportRequest;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.dataexchange.domain.Job;
import cn.timebank.dataexchange.domain.JobKind;
import cn.timebank.dataexchange.infrastructure.JobRepository;
import cn.timebank.file.application.FileStorage;
import cn.timebank.file.application.dto.DownloadResult;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.TimeBankPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Service
class ExportServiceImpl implements ExportService {

    static final Set<String> SUPPORTED_REPORT_TYPES = Set.of(
            "participation", "completion", "serviceHours", "reviewEfficiency", "topProjects", "serviceRecords");

    private final JobRepository jobRepository;
    private final ExportJobRunner exportJobRunner;
    private final FileStorage fileStorage;
    private final ExportDownloadTokenService tokenService;

    ExportServiceImpl(JobRepository jobRepository, ExportJobRunner exportJobRunner, FileStorage fileStorage,
                       ExportDownloadTokenService tokenService) {
        this.jobRepository = jobRepository;
        this.exportJobRunner = exportJobRunner;
        this.fileStorage = fileStorage;
        this.tokenService = tokenService;
    }

    // Deliberately not @Transactional: jobRepository.saveAndFlush runs (and
    // commits) in its own implicit repository-method transaction, so the
    // row is durably visible to the async runner's own connection before
    // runAsync fires. Wrapping this method in @Transactional would defer
    // that commit until the whole method returns — *after* the async call
    // already dispatched to another thread — a real race caught live: the
    // runner's claim() intermittently threw "Job not found" because its
    // own transaction couldn't see the still-uncommitted insert. See
    // 00-Status.md §18.
    @Override
    public JobDto create(CreateExportRequest request, String actorId) {
        if (!SUPPORTED_REPORT_TYPES.contains(request.reportType())) {
            throw ApiException.invalidRequest("Unknown export report type: " + request.reportType());
        }
        Job job = new Job(OpaqueIds.generate("job"), JobKind.EXPORT, request.reportType(), "QUEUED", actorId, Instant.now());
        jobRepository.saveAndFlush(job);
        exportJobRunner.runAsync(job.getId());
        return toDto(job);
    }

    @Override
    @Transactional(readOnly = true)
    public JobDto get(String jobId, Authentication auth) {
        return toDto(findAccessibleOrThrow(jobId, auth));
    }

    @Override
    @Transactional(readOnly = true)
    public String issueDownloadUrl(String jobId, Authentication auth) {
        Job job = findAccessibleOrThrow(jobId, auth);
        if (!"SUCCEEDED".equals(job.getStatus()) || job.getResultRef() == null) {
            throw ApiException.stateConflict("Export is not ready for download yet.");
        }
        String token = tokenService.issue(job.getId());
        return "/api/v1/exports/" + job.getId() + "/content?token=" + token;
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadResult download(String token) {
        String jobId = tokenService.validate(token);
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Export not found."));
        if (job.getResultRef() == null) {
            throw ApiException.notFound("Export not found.");
        }
        return new DownloadResult(fileStorage.open(job.getResultRef()), job.getResourceType() + ".csv", "text/csv");
    }

    private Job findAccessibleOrThrow(String jobId, Authentication auth) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Export not found."));
        TimeBankPrincipal principal = (CookieSessionUserDetails) auth.getPrincipal();
        boolean isAdmin = principal.roleCodes().contains("admin") || principal.roleCodes().contains("sysadmin");
        if (!isAdmin && !job.getCreatedBy().equals(principal.id())) {
            throw ApiException.notFound("Export not found.");
        }
        return job;
    }

    private JobDto toDto(Job job) {
        return new JobDto(job.getId(), job.getKind().name(), job.getResourceType(), job.getStatus(), job.getCreatedBy(),
                job.getTotalRows(), job.getValidRows(), job.getProcessedRows(), job.getCheckpointOffset(),
                job.getErrorMessage(), job.getVersion(), job.getCreatedAt(), job.getUpdatedAt(), job.getFinishedAt());
    }
}
