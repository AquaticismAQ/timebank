package cn.timebank.lifecycle.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.lifecycle.application.SchoolLifecycleConfigService;
import cn.timebank.lifecycle.application.dto.SchoolLifecycleConfigDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /lifecycle/config} — the school-year facts that are configured
 * rather than stored (`APIContract.md` §4.17).
 *
 * <p>{@code isAuthenticated()} rather than a permission, matching
 * {@code GET /academic-years}: this is the same class of structural fact about
 * the school, it names no person and no record, and both of its callers are
 * ordinary screens rather than operational controls. A permission here would
 * only mean some staff member's grade filter silently loses its graduate
 * options.</p>
 */
@RestController
public class SchoolLifecycleConfigController {

    private final SchoolLifecycleConfigService service;

    public SchoolLifecycleConfigController(SchoolLifecycleConfigService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/lifecycle/config")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<SchoolLifecycleConfigDto> get() {
        return ApiResponse.of(service.get());
    }
}
