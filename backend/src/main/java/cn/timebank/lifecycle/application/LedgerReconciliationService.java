package cn.timebank.lifecycle.application;

import cn.timebank.lifecycle.application.dto.ReconciliationRunResultDto;

/** B6.7/D12 — R4 names silent ledger drift as the most damaging failure this system could have. */
public interface LedgerReconciliationService {

    /** Nightly: only accounts touched since the last checkpoint. */
    ReconciliationRunResultDto runCheckpoint();

    /** Weekly: every account, regardless of recent activity — the safety net against a bug in the checkpoint path itself missing something. */
    ReconciliationRunResultDto runFull();
}
