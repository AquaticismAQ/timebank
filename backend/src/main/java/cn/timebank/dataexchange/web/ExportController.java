package cn.timebank.dataexchange.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.dataexchange.application.ExportService;
import cn.timebank.dataexchange.application.dto.DataExchangeRequests.CreateExportRequest;
import cn.timebank.dataexchange.application.dto.DownloadUrlDto;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.file.application.dto.DownloadResult;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contract §4.13. `content` (the actual byte stream) is one endpoint beyond the contract's literal table — the same shape as `file`'s `/{id}/content?token=`, needed because `/download` itself only returns a URL, not bytes, on a local-disk deployment with no native presigned-URL support (B3F). */
@RestController
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * One permission per export type, not a blanket {@code exports.create}
     * (`Design/english/10-RolesAndPermissions.md` §5.1: a teacher who needs
     * the student list must not thereby gain Review Efficiency, whose every
     * row is a colleague's approval rate). The authority name is the export
     * type's stable key, so the check is {@code exports.} + the requested
     * type — the type is only a value here, never re-parsed as SpEL.
     *
     * <p>Accepted knowingly (`11-DataImportExportOptimization.md` §3.1):
     * because authorization runs before the method body, an unknown or
     * misspelled report type is a <b>403</b> — no such permission exists —
     * rather than the service's 400. Right precedence for an unknown
     * permission, confusing status for a typo. A type whose permission the
     * caller does hold but which no query serves yet still reaches the
     * service and gets its 400. A blank type is also still a 400, because
     * {@code @Valid} runs during argument resolution, ahead of this.</p>
     */
    @PostMapping("/api/v1/exports")
    @PreAuthorize("hasAuthority('exports.' + #request.reportType())")
    public ApiResponse<JobDto> create(@Valid @RequestBody CreateExportRequest request, Authentication auth) {
        return ApiResponse.of(exportService.create(request, principalId(auth)));
    }

    @GetMapping("/api/v1/exports/{jobId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<JobDto> get(@PathVariable String jobId, Authentication auth) {
        return ApiResponse.of(exportService.get(jobId, auth));
    }

    @GetMapping("/api/v1/exports/{jobId}/download")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<DownloadUrlDto> download(@PathVariable String jobId, Authentication auth) {
        return ApiResponse.of(new DownloadUrlDto(exportService.issueDownloadUrl(jobId, auth)));
    }

    @GetMapping("/api/v1/exports/{jobId}/content")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InputStreamResource> content(@PathVariable String jobId, @RequestParam String token) {
        DownloadResult result = exportService.download(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .body(new InputStreamResource(result.content()));
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
