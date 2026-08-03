package cn.timebank.file.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.file.application.FileService;
import cn.timebank.file.application.dto.DownloadResult;
import cn.timebank.file.application.dto.FileMetadataDto;
import cn.timebank.file.application.dto.FileRequests.CreateUploadIntentRequest;
import cn.timebank.file.application.dto.UploadIntentDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Contract §4.6. {@code PUT /files/{id}/content} substitutes for a direct
 * client-to-object-store PUT in this test-stage, local-disk pass — the app
 * proxies bytes here, which D9 says a real S3-compatible adapter should
 * not need to do (`14-Backend-B3F-CloudFileStorage.md`).
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload-intents")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<UploadIntentDto> createUploadIntent(
            @Valid @RequestBody CreateUploadIntentRequest request, Authentication auth) {
        return ApiResponse.of(fileService.createUploadIntent(request, principalId(auth)));
    }

    @PutMapping("/{id}/content")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> uploadContent(@PathVariable String id, HttpServletRequest request, Authentication auth) {
        try {
            fileService.receiveContent(id, principalId(auth), request.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ApiResponse.of(null);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<FileMetadataDto> complete(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(fileService.complete(id, principalId(auth)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<FileMetadataDto> get(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(fileService.getMetadata(id, auth));
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id, @RequestParam String token) {
        DownloadResult result = fileService.downloadContent(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + result.filename() + "\"")
                .body(new InputStreamResource(result.content()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> delete(@PathVariable String id, Authentication auth) {
        fileService.delete(id, auth);
        return ApiResponse.of(null);
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
