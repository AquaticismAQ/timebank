package cn.timebank.dataexchange.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
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
import java.util.Map;
import java.util.Set;

@Service
class ExportServiceImpl implements ExportService {

    /**
     * The export types this phase can serve honestly. V31 carries a
     * permission for all seven of `12-DataExport.md`'s types, but five of
     * them have no query yet (phases 3-4 of
     * `11-DataImportExportOptimization.md`), and registering a key nothing
     * can write would hand the caller a job that always ends FAILED — worse
     * than a 400 saying so. The pre-split keys `participation`,
     * `completion`, `serviceHours`, `reviewEfficiency` and `topProjects`
     * stop being accepted here: two retire outright (§2.4) and three are
     * the right subject in the wrong shape, to be re-cut per type.
     */
    static final Set<String> SUPPORTED_REPORT_TYPES = Set.of("studentList", "serviceRecords");

    /**
     * `studentList` is the design's stable key for the roster export
     * (10-RolesAndPermissions.md §5.2) and is what the request body, the
     * permission code and the audit trail all use. {@code ExportJobRunner}
     * still dispatches on the pre-split name {@code students}, and rewriting
     * that runner into a per-type dispatcher is phase 2's work — so the
     * stored {@code resource_type} keeps the old name for this one type
     * rather than renaming the key only where it would make every
     * studentList job fail. Delete this map when the runner switches to the
     * new keys; {@code ImportExportIT} exports a studentList over HTTP and
     * downloads the file, so the seam is covered from both ends.
     */
    private static final Map<String, String> RUNNER_RESOURCE_TYPES = Map.of("studentList", "students");

    private final JobRepository jobRepository;
    private final ExportJobRunner exportJobRunner;
    private final FileStorage fileStorage;
    private final ExportDownloadTokenService tokenService;
    private final AuditService auditService;

    ExportServiceImpl(JobRepository jobRepository, ExportJobRunner exportJobRunner, FileStorage fileStorage,
                       ExportDownloadTokenService tokenService, AuditService auditService) {
        this.jobRepository = jobRepository;
        this.exportJobRunner = exportJobRunner;
        this.fileStorage = fileStorage;
        this.tokenService = tokenService;
        this.auditService = auditService;
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
        String resourceType = RUNNER_RESOURCE_TYPES.getOrDefault(request.reportType(), request.reportType());
        Job job = new Job(OpaqueIds.generate("job"), JobKind.EXPORT, resourceType, "QUEUED", actorId, Instant.now());
        jobRepository.saveAndFlush(job);
        // F-9 / phase 5 exit criterion 1. An export is the one operation that
        // takes student data *out* of the system, and it was until now the only
        // significant action leaving no trace beyond a `job` row that retention
        // later strips. The permission is recorded too, because "who was allowed
        // to do this" is the question asked afterwards, and the per-type split
        // (V31) is what makes the answer specific.
        //
        // Recorded on request rather than on completion: the request is the
        // decision a person made, and a job that fails still says someone asked
        // for the file. Requested is also the state under which the row must
        // exist, so an export that dies mid-run is not invisible.
        auditService.record(new AuditEntry(actorId, "exports." + request.reportType(),
                "export.create", "export", job.getId(), null,
                new ExportRequested(request.reportType(), resourceType), null));
        exportJobRunner.runAsync(job.getId());
        return toDto(job);
    }

    /**
     * The audit row's {@code after} summary. `reportType` is what was asked for
     * and `resourceType` is what the runner was given — the same string for
     * every type except `studentList`, whose stopgap rename would otherwise make
     * the audit trail disagree with the request for no visible reason.
     */
    private record ExportRequested(String reportType, String resourceType) {
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
        boolean hasFullScope = principal.hasFullDataScope(); // 07-RoleFinetune.md §2.1
        if (!hasFullScope && !job.getCreatedBy().equals(principal.id())) {
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
