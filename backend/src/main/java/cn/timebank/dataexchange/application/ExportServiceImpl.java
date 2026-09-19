package cn.timebank.dataexchange.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.error.FieldErrorDetail;
import cn.timebank.dataexchange.application.dto.DataExchangeRequests.CreateExportRequest;
import cn.timebank.dataexchange.application.dto.DataExchangeRequests.ExportFilters;
import cn.timebank.dataexchange.application.dto.ExportTypeDto;
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
import java.util.List;
import java.util.Set;

@Service
class ExportServiceImpl implements ExportService {

    /**
     * The export types a query exists for today, read from {@link ExportTypeCatalog}
     * rather than duplicated here.
     *
     * <p>All seven types are permissioned and published; five have no query yet
     * (phases 3-4), and asking for one of those is a 400 saying so rather than a
     * job that ends FAILED. The pre-split keys — {@code participation},
     * {@code completion}, {@code serviceHours}, {@code reviewEfficiency} as it was
     * shaped, and {@code topProjects} — are simply absent from the catalogue: two
     * retire outright (§2.4) and the rest are re-cut per type.</p>
     */
    static final Set<String> SUPPORTED_REPORT_TYPES = ExportTypeCatalog.TYPES.stream()
            .filter(ExportTypeCatalog.ExportType::implemented)
            .map(ExportTypeCatalog.ExportType::key)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());


    private final JobRepository jobRepository;
    private final ExportJobRunner exportJobRunner;
    private final FileStorage fileStorage;
    private final ExportDownloadTokenService tokenService;
    private final AuditService auditService;
    private final ExportScopeResolver scopeResolver;
    private final ExportJobParameters jobParameters;

    ExportServiceImpl(JobRepository jobRepository, ExportJobRunner exportJobRunner, FileStorage fileStorage,
                       ExportDownloadTokenService tokenService, AuditService auditService,
                       ExportScopeResolver scopeResolver, ExportJobParameters jobParameters) {
        this.jobRepository = jobRepository;
        this.exportJobRunner = exportJobRunner;
        this.fileStorage = fileStorage;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.scopeResolver = scopeResolver;
        this.jobParameters = jobParameters;
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
    public JobDto create(CreateExportRequest request, Authentication auth) {
        ExportTypeCatalog.ExportType type = ExportTypeCatalog.find(request.reportType())
                // Unreachable through the controller — no permission exists for an
                // unknown type, so @PreAuthorize already returned 403 — but a direct
                // caller must not get past here either.
                .orElseThrow(() -> ApiException.invalidRequest("Unknown export report type: " + request.reportType()));
        if (!type.implemented()) {
            throw ApiException.invalidRequest("Export type is not available yet: " + request.reportType());
        }

        ExportFilters filters = request.filtersOrEmpty();
        rejectUndeclaredFilters(type, filters);

        String actorId = principalId(auth);
        ExportScope scope = scopeResolver.resolve(principal(auth), filters);
        String paramsJson = jobParameters.write(scope);

        Job job = new Job(OpaqueIds.generate("job"), JobKind.EXPORT, type.key(), "QUEUED", actorId, Instant.now());
        job.setParamsJson(paramsJson);
        jobRepository.saveAndFlush(job);
        // F-9 / phase 5 exit criterion 1. An export is the one operation that
        // takes student data *out* of the system, and it was until now the only
        // significant action leaving no trace beyond a `job` row that retention
        // later strips. The permission is recorded too, because "who was allowed
        // to do this" is the question asked afterwards, and the per-type split
        // (V31) is what makes the answer specific.
        //
        // The filters go in as the serialized scope, so §2.6's promise holds: an
        // export is reconstructable from the audit log alone, rather than the log
        // recording only that *some* file was taken.
        //
        // Recorded on request rather than on completion: the request is the
        // decision a person made, and a job that fails still says someone asked
        // for the file.
        auditService.record(new AuditEntry(actorId, "exports." + type.key(),
                "export.create", "export", job.getId(), null,
                new ExportRequested(type.key(), paramsJson), null));
        exportJobRunner.runAsync(job.getId());
        return toDto(job);
    }

    /**
     * Refuses a filter the requested type does not declare (§2.6.1).
     *
     * <p>The alternative — accepting it and not applying it — is the failure issue
     * 093 §1 existed to remove: a filter that is silently dropped is
     * indistinguishable from one that works, and the operator cannot tell from the
     * file that their narrowing did nothing. A field error names the offending
     * field so a client author sees immediately which type does not have it.</p>
     */
    private void rejectUndeclaredFilters(ExportTypeCatalog.ExportType type, ExportFilters filters) {
        List<FieldErrorDetail> errors = new java.util.ArrayList<>();
        checkDeclared(type, filters.academicYearId(), ExportTypeCatalog.Filter.ACADEMIC_YEAR, errors);
        checkDeclared(type, filters.gradeId(), ExportTypeCatalog.Filter.GRADE, errors);
        checkDeclared(type, filters.classId(), ExportTypeCatalog.Filter.CLASS, errors);
        checkDeclared(type, filters.reviewerId(), ExportTypeCatalog.Filter.REVIEWER, errors);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed("Export filters failed validation.", errors);
        }
    }

    private void checkDeclared(ExportTypeCatalog.ExportType type, String value,
                               ExportTypeCatalog.Filter filter, List<FieldErrorDetail> errors) {
        if (value != null && !type.filters().contains(filter)) {
            errors.add(new FieldErrorDetail("filters." + filter.field(), "NOT_SUPPORTED",
                    "The " + type.key() + " export has no " + filter.field() + " filter."));
        }
    }

    /**
     * Only the types the caller holds a permission for
     * (`12-DataExport.md` §3: a type the user lacks renders nothing, not a
     * disabled card). Filtering here rather than in the client means the list is
     * also the authorisation answer, so the two cannot disagree.
     */
    @Override
    public List<ExportTypeDto> availableTypes(Authentication auth) {
        java.util.Set<String> authorities = auth.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        return ExportTypeCatalog.TYPES.stream()
                .filter(t -> authorities.contains("exports." + t.key()))
                .map(t -> new ExportTypeDto(t.key(), t.titleKey(), t.descriptionKey(),
                        t.filters().stream().map(ExportTypeCatalog.Filter::field).toList(),
                        t.implemented()))
                .toList();
    }

    /**
     * The audit row's {@code after} summary: what was asked for, and the scope it
     * was resolved to. The second half is what makes the entry answer "which
     * students did that file contain" rather than only "a file was taken".
     */
    private record ExportRequested(String reportType, String scope) {
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

    private static TimeBankPrincipal principal(Authentication auth) {
        return (CookieSessionUserDetails) auth.getPrincipal();
    }

    private static String principalId(Authentication auth) {
        return principal(auth).id();
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
