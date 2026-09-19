package cn.timebank.dataexchange.application.dto;

import jakarta.validation.constraints.NotBlank;

public final class DataExchangeRequests {

    private DataExchangeRequests() {
    }

    /** `POST /exports` (contract §4.13). {@code reportType} is an export type's stable key, and also names the permission required to ask for it — {@code exports.<reportType>} (10-RolesAndPermissions.md §5.2). {@code ExportServiceImpl.SUPPORTED_REPORT_TYPES} is the set a query exists for today. */
    public record CreateExportRequest(@NotBlank String reportType) {
    }

    /** `POST /imports` (contract §4.14). {@code type} names the import template; only {@code serviceRecordsHistorical} exists in this pass (Design.md R6's launch import). */
    public record CreateImportRequest(@NotBlank String type) {
    }
}
