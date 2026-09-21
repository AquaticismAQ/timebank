package cn.timebank.dataexchange.application.dto;

import jakarta.validation.constraints.NotBlank;

public final class DataExchangeRequests {

    private DataExchangeRequests() {
    }

    /** `POST /exports` (contract §4.13). {@code reportType} is an export type's stable key, and also names the permission required to ask for it — {@code exports.<reportType>} (11-RolesAndPermissions.md §5.2). {@code ExportServiceImpl.SUPPORTED_REPORT_TYPES} is the set a query exists for today. */
    public record CreateExportRequest(@NotBlank String reportType, ExportFilters filters) {

        /** Filters are optional in the body; absent and all-null mean the same thing — every dimension unrestricted. */
        public ExportFilters filtersOrEmpty() {
            return filters == null ? ExportFilters.none() : filters;
        }
    }

    /**
     * The filter dimensions an export can be narrowed by
     * (`11-DataImportExportOptimization.md` §2.6).
     *
     * <p>Every field is nullable and {@code null} means <b>All</b> (§4.1) — not
     * "unset, apply a default". The one documented exception is Student
     * Completion's academic year, which defaults to the current year because an
     * annual completion rate across all years has no denominator (§4.2).</p>
     *
     * <p>Which of these a given export actually accepts is declared by
     * {@code ExportTypeCatalog}, and passing one an export does not declare is a
     * field error rather than a silently dropped filter.</p>
     */
    public record ExportFilters(String academicYearId, String gradeId, String classId, String reviewerId) {

        public static ExportFilters none() {
            return new ExportFilters(null, null, null, null);
        }

        public boolean isEmpty() {
            return academicYearId == null && gradeId == null && classId == null && reviewerId == null;
        }
    }

    /** `POST /imports` (contract §4.14). {@code type} names the import template; only {@code serviceRecordsHistorical} exists in this pass (Design.md R6's launch import). */
    public record CreateImportRequest(@NotBlank String type) {
    }
}
