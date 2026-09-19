package cn.timebank.dataexchange.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.dataexchange.application.HistoricalImportWindowService;
import cn.timebank.dataexchange.application.ImportService;
import cn.timebank.dataexchange.application.dto.HistoricalImportWindowDto;
import cn.timebank.dataexchange.application.dto.ImportErrorReportDto;
import cn.timebank.dataexchange.application.dto.JobDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Contract §4.14. */
@RestController
public class ImportController {

    private final ImportService importService;
    private final HistoricalImportWindowService windowService;

    public ImportController(ImportService importService, HistoricalImportWindowService windowService) {
        this.importService = importService;
        this.windowService = windowService;
    }

    /**
     * 11-DataImport.md §6.2. Mapped above {@code /imports/{id}} in this file
     * only for readability — the literal path wins over the template either
     * way, so {@code window} is not mistaken for a job id.
     *
     * <p>Gated on {@code imports.read} rather than {@code imports.create}: this
     * is the read the screen uses to decide whether to draw the card at all,
     * and anyone who may look at import jobs may see whether the feature is
     * still open.</p>
     */
    @GetMapping("/api/v1/imports/window")
    @PreAuthorize("hasAuthority('imports.read')")
    public ApiResponse<HistoricalImportWindowDto> window() {
        return ApiResponse.of(windowService.state());
    }

    @GetMapping("/api/v1/imports/templates/{type}")
    @PreAuthorize("hasAuthority('imports.create')")
    public ResponseEntity<String> template(@PathVariable String type) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + type + "-template.csv\"")
                .body(importService.template(type));
    }

    @PostMapping(value = "/api/v1/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('imports.create')")
    public ApiResponse<JobDto> create(@RequestParam("type") String type, @RequestParam("file") MultipartFile file,
                                       Authentication auth) {
        return ApiResponse.of(importService.create(type, file, principalId(auth)));
    }

    @GetMapping("/api/v1/imports/{id}")
    @PreAuthorize("hasAuthority('imports.read')")
    public ApiResponse<JobDto> get(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(importService.get(id, auth));
    }

    @PostMapping("/api/v1/imports/{id}/validate")
    @PreAuthorize("hasAuthority('imports.create')")
    public ApiResponse<JobDto> validate(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(importService.validate(id, principalId(auth)));
    }

    @GetMapping("/api/v1/imports/{id}/errors")
    @PreAuthorize("hasAuthority('imports.read')")
    public ApiResponse<ImportErrorReportDto> errors(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(importService.errors(id, auth));
    }

    @PostMapping("/api/v1/imports/{id}/commit")
    @PreAuthorize("hasAuthority('imports.commit')")
    public ApiResponse<JobDto> commit(@PathVariable String id, @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       Authentication auth) {
        return ApiResponse.of(importService.commit(id, principalId(auth), idempotencyKey));
    }

    @PostMapping("/api/v1/imports/{id}/cancel")
    @PreAuthorize("hasAuthority('imports.create')")
    public ApiResponse<JobDto> cancel(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(importService.cancel(id, principalId(auth)));
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
