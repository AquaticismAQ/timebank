package cn.timebank.dataexchange.application.dto;

import jakarta.validation.constraints.NotBlank;

public final class DataExchangeRequests {

    private DataExchangeRequests() {
    }

    /** `POST /exports` (contract §4.13). {@code reportType} is one of the five `/reports/*` names, or {@code serviceRecords} for the full raw ledger (Design.md §16.3's cohort-preservation export). */
    public record CreateExportRequest(@NotBlank String reportType) {
    }

    /** `POST /imports` (contract §4.14). {@code type} names the import template; only {@code serviceRecordsHistorical} exists in this pass (Design.md R6's launch import). */
    public record CreateImportRequest(@NotBlank String type) {
    }
}
