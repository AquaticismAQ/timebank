package cn.timebank.lifecycle.application.dto;

import java.util.List;

public record ReconciliationRunResultDto(String runId, String mode, int accountsChecked, List<Mismatch> mismatches) {

    public record Mismatch(String accountId, int storedBalance, int computedBalance, String divergentTransactionId) {
    }
}
