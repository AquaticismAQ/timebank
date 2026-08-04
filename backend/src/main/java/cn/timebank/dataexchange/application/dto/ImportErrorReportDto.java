package cn.timebank.dataexchange.application.dto;

import java.util.List;

/** `GET /imports/{id}/errors` (contract §4.14) — capped at the first 1,000 rows; the full report is also downloadable as `errorRef`'s CSV via the same content mechanism as exports. */
public record ImportErrorReportDto(int totalErrors, List<RowError> errors) {

    public record RowError(int rowNumber, String field, String code, String message) {
    }
}
