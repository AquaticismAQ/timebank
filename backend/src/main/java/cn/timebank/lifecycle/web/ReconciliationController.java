package cn.timebank.lifecycle.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.lifecycle.application.LedgerReconciliationService;
import cn.timebank.lifecycle.application.dto.ReconciliationRunResultDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** B6.7. Operational control, not an `APIContract.md` resource — same rationale as {@code PurgeController}. */
@RestController
public class ReconciliationController {

    private final LedgerReconciliationService reconciliationService;

    public ReconciliationController(LedgerReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/api/v1/admin/reconciliation-runs")
    @PreAuthorize("hasAuthority('lifecycle.reconcile')")
    public ApiResponse<ReconciliationRunResultDto> run(@RequestParam(defaultValue = "false") boolean full) {
        return ApiResponse.of(full ? reconciliationService.runFull() : reconciliationService.runCheckpoint());
    }
}
