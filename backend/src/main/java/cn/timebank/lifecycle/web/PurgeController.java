package cn.timebank.lifecycle.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.lifecycle.application.CohortPurgeService;
import cn.timebank.lifecycle.application.dto.PurgeRunSummaryDto;
import cn.timebank.security.application.CookieSessionUserDetails;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B6.3/B6.4 (Design.md §16.3). Not in `APIContract.md` — B6 is
 * infrastructure hardening, not a new product surface, so this endpoint is
 * an operational control, not a contract resource. {@code dryRun} defaults
 * to {@code true}: a real deletion requires an explicit, authenticated
 * admin to ask for it by name, deliberately, every time (never a query
 * param an automated script could default away).
 */
@RestController
public class PurgeController {

    private final CohortPurgeService cohortPurgeService;

    public PurgeController(CohortPurgeService cohortPurgeService) {
        this.cohortPurgeService = cohortPurgeService;
    }

    @PostMapping("/api/v1/admin/purge-runs")
    @PreAuthorize("hasAuthority('lifecycle.purge')")
    public ApiResponse<List<PurgeRunSummaryDto>> run(
            @RequestParam(defaultValue = "true") boolean dryRun, Authentication auth) {
        String actorId = ((CookieSessionUserDetails) auth.getPrincipal()).id();
        return ApiResponse.of(cohortPurgeService.run(dryRun, actorId));
    }
}
