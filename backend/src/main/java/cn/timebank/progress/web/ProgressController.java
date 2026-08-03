package cn.timebank.progress.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.progress.application.ProgressService;
import cn.timebank.progress.application.dto.ProgressDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Contract §4.9. Owner-or-staff check happens inside the service (B1.8's 404-not-403 convention). */
@RestController
public class ProgressController {

    private final ProgressService progressService;

    public ProgressController(ProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping("/api/v1/students/{id}/progress")
    @PreAuthorize("hasAuthority('progress.read')")
    public ApiResponse<ProgressDto> get(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(progressService.getProgress(id, auth));
    }
}
