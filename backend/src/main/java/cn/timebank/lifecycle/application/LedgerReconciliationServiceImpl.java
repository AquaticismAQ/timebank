package cn.timebank.lifecycle.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.lifecycle.application.dto.ReconciliationRunResultDto;
import cn.timebank.lifecycle.application.dto.ReconciliationRunResultDto.Mismatch;
import cn.timebank.lifecycle.infrastructure.LedgerReconciliationRepository;
import cn.timebank.lifecycle.infrastructure.LedgerReconciliationRepository.MismatchCheck;
import cn.timebank.lifecycle.infrastructure.ReconciliationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
class LedgerReconciliationServiceImpl implements LedgerReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(LedgerReconciliationServiceImpl.class);

    private final LedgerReconciliationRepository ledgerRepository;
    private final ReconciliationRunRepository runRepository;

    LedgerReconciliationServiceImpl(LedgerReconciliationRepository ledgerRepository, ReconciliationRunRepository runRepository) {
        this.ledgerRepository = ledgerRepository;
        this.runRepository = runRepository;
    }

    @Override
    public ReconciliationRunResultDto runCheckpoint() {
        Instant checkpointBefore = runRepository.currentCheckpoint();
        Instant checkpointAfter = Instant.now();
        List<String> accountIds = ledgerRepository.accountIdsTouchedSince(checkpointBefore);
        ReconciliationRunResultDto result = execute("CHECKPOINT", checkpointBefore, accountIds);
        runRepository.advanceCheckpoint(checkpointAfter);
        return result;
    }

    @Override
    public ReconciliationRunResultDto runFull() {
        return execute("FULL", null, ledgerRepository.allAccountIds());
    }

    private ReconciliationRunResultDto execute(String mode, Instant checkpointBefore, List<String> accountIds) {
        String runId = OpaqueIds.generate("recon");
        runRepository.start(runId, mode, checkpointBefore, Instant.now());
        try {
            List<Mismatch> mismatches = new ArrayList<>();
            List<MismatchCheck> checks = new ArrayList<>();
            for (String accountId : accountIds) {
                Optional<MismatchCheck> mismatch = ledgerRepository.check(accountId);
                mismatch.ifPresent(m -> {
                    checks.add(m);
                    mismatches.add(new Mismatch(m.accountId(), m.storedBalance(), m.computedBalance(), m.divergentTransactionId()));
                    log.error("LEDGER RECONCILIATION MISMATCH: account {} stored balance {} != computed balance {} " +
                                    "(first divergent transaction: {})",
                            m.accountId(), m.storedBalance(), m.computedBalance(), m.divergentTransactionId());
                });
            }
            runRepository.recordMismatches(runId, checks);
            runRepository.complete(runId, accountIds.size(), mismatches.size(), Instant.now());
            log.info("Reconciliation run {} ({}) complete: {} accounts checked, {} mismatches", runId, mode, accountIds.size(), mismatches.size());
            return new ReconciliationRunResultDto(runId, mode, accountIds.size(), mismatches);
        } catch (Exception e) {
            log.error("Reconciliation run {} ({}) failed", runId, mode, e);
            runRepository.fail(runId, String.valueOf(e.getMessage()));
            throw e;
        }
    }
}
