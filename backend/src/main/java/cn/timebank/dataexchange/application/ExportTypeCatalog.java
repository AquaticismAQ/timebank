package cn.timebank.dataexchange.application;

import java.util.List;
import java.util.Optional;

/**
 * The seven export types of `Design/*&#47;13-DataExport.md` §6, and — the reason
 * this class exists — <b>which filters each one accepts</b>
 * (`11-DataImportExportOptimization.md` §2.6.1).
 *
 * <p>The request body is a flat object of nullable filter fields, and nothing in
 * that shape says which of them a given export understands. Without a
 * declaration somewhere, {@code POST /exports} would take a {@code classId} on
 * Review History — an export with no class dimension at all — and silently drop
 * it. A filter that is accepted and then ignored is indistinguishable from one
 * that works, which is the exact failure issue 093 §1 existed to remove.</p>
 *
 * <p>Declaring it here rather than in the frontend means adding an eighth export
 * later is one server-side edit, and the card appears with the right controls
 * without a second codebase agreeing to change. {@code GET /exports/types}
 * publishes this list; the client renders what it is told.</p>
 *
 * <p><b>Column names are not here.</b> §2.6 fixes them as English in code beside
 * each query, while titles and card texts are i18n keys — the two pull in
 * opposite directions and conflating them is the most likely way to get this
 * wrong while "making the export translatable".</p>
 */
final class ExportTypeCatalog {

    /** A filter dimension an export can declare. The name is the request field and the i18n suffix. */
    enum Filter {
        ACADEMIC_YEAR("academicYearId"),
        GRADE("gradeId"),
        CLASS("classId"),
        REVIEWER("reviewerId");

        private final String field;

        Filter(String field) {
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    /**
     * @param key        the stable request key, and the {@code exports.<key>} permission suffix
     * @param filters    every filter this export accepts; anything else is a field error
     * @param implemented whether a query exists yet — see {@link #IMPLEMENTED_NOTE}
     */
    record ExportType(String key, List<Filter> filters, boolean implemented) {

        /** i18n key for the card title; the bundle owns the wording, this owns the mapping. */
        String titleKey() {
            return "admin.system.exportType" + capitalized();
        }

        /** i18n key for the card's explanatory paragraph (§6's card text, not a label). */
        String descriptionKey() {
            return "admin.system.exportType" + capitalized() + "Desc";
        }

        private String capitalized() {
            return Character.toUpperCase(key.charAt(0)) + key.substring(1);
        }
    }

    /**
     * All seven now have a query. The flag stays because it is the honest way to
     * publish a type whose permission exists before its file does — a client can
     * draw the card and say so, rather than finding out from a job that ends
     * FAILED.
     */
    private static final List<Filter> STUDENT_FILTERS =
            List.of(Filter.ACADEMIC_YEAR, Filter.GRADE, Filter.CLASS);

    static final List<ExportType> TYPES = List.of(
            new ExportType("studentList", STUDENT_FILTERS, true),
            new ExportType("serviceRecords", STUDENT_FILTERS, true),
            new ExportType("studentCompletion", STUDENT_FILTERS, true),
            new ExportType("studentParticipation", STUDENT_FILTERS, true),
            // Projects rows are projects, not students, so there is no class dimension.
            new ExportType("projects", List.of(Filter.ACADEMIC_YEAR, Filter.GRADE), true),
            new ExportType("reviewEfficiency", List.of(Filter.ACADEMIC_YEAR, Filter.GRADE), true),
            // Review History is filtered by who decided, never by whose class they taught.
            new ExportType("reviewHistory", List.of(Filter.ACADEMIC_YEAR, Filter.REVIEWER), true));

    private ExportTypeCatalog() {
    }

    static Optional<ExportType> find(String key) {
        return TYPES.stream().filter(t -> t.key().equals(key)).findFirst();
    }

    static boolean isImplemented(String key) {
        return find(key).map(ExportType::implemented).orElse(false);
    }
}
