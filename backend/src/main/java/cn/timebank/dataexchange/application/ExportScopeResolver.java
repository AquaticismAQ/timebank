package cn.timebank.dataexchange.application;

import cn.timebank.common.error.ApiException;
import cn.timebank.common.error.FieldErrorDetail;
import cn.timebank.dataexchange.application.dto.DataExchangeRequests.ExportFilters;
import cn.timebank.dataexchange.infrastructure.ExportScopeRepository;
import cn.timebank.security.application.DataScopeResolver;
import cn.timebank.security.application.ScopeDescriptor;
import cn.timebank.security.application.TimeBankPrincipal;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Turns "who is asking" plus "what they filtered by" into a concrete set of
 * students, once per job (`11-DataImportExportOptimization.md` §2.5, the
 * concrete form of `12-DataExport.md` §4.4).
 *
 * <p><b>Why a student set and not a grade predicate.</b> Records carry a grade
 * <em>snapshot</em> — the grade the student was in when the record was filed —
 * and a teacher's scope is about <em>students</em>. Filtering service records by
 * snapshot grade silently drops a record a student filed last year in a grade
 * their present teacher is not bound to, even though that student is in their
 * class today. Resolving to students once, here, and having every query join
 * against that set is what makes §4.4's case come out right instead of
 * accidentally.</p>
 *
 * <p>Deliberately not reusing {@code DataScopeSpecifications}: it produces JPA
 * {@code Specification}s and the export path is raw JDBC with keyset pagination
 * that `12-DataExport.md` §2.1 keeps on purpose.</p>
 */
@Component
class ExportScopeResolver {

    private final DataScopeResolver dataScopeResolver;
    private final ExportScopeRepository scopeRepository;

    ExportScopeResolver(DataScopeResolver dataScopeResolver, ExportScopeRepository scopeRepository) {
        this.dataScopeResolver = dataScopeResolver;
        this.scopeRepository = scopeRepository;
    }

    /**
     * @param principal the requester
     * @param filters   their chosen filters; each can only ever <em>narrow</em> (§4.3)
     */
    ExportScope resolve(TimeBankPrincipal principal, ExportFilters filters) {
        int currentYearIndex = scopeRepository.currentAcademicYearStartYear();
        Integer selectedYearIndex = resolveSelectedYear(filters.academicYearId(), currentYearIndex);

        ScopeDescriptor descriptor =
                dataScopeResolver.resolve(principal, "students", principal.hasSchoolWideVisibility());

        Set<String> boundGradeIds = null;
        Set<String> boundClassIds = null;
        switch (descriptor) {
            case ScopeDescriptor.All ignored -> {
                // No structural restriction; the caller's own filters still apply below.
            }
            case ScopeDescriptor.Grades grades -> boundGradeIds = grades.gradeIds();
            case ScopeDescriptor.Classes classes -> boundClassIds = classes.classIds();
            // §7.3: a teacher with no grade assignment is refused outright rather
            // than handed an empty file. An empty CSV is indistinguishable from
            // "nothing happened that year", and the whole point of the rule is
            // that those two must not look alike.
            case ScopeDescriptor.Self ignored -> throw ApiException.forbidden(
                    "You have not been assigned any grade, so there is no student data to export. "
                            + "Ask a Student Development Centre administrator to assign your grades.");
            // Exports have no project dimension in their scope; reaching here means
            // the scope namespace was wired wrong, and falling through would mean
            // exporting the whole school.
            case ScopeDescriptor.Projects ignored -> throw new IllegalStateException(
                    "Project scope is not a valid data scope for exports");
        }

        String gradeFilter = requireWithinBounds(filters.gradeId(), boundGradeIds, "filters.gradeId",
                "grade is outside the grades assigned to you");
        String classFilter = requireWithinBounds(filters.classId(), boundClassIds, "filters.classId",
                "class is outside the classes assigned to you");
        requireExists(gradeFilter, scopeRepository::gradeExists, "filters.gradeId", "grade");
        requireExists(classFilter, scopeRepository::classExists, "filters.classId", "class");
        requireExists(filters.reviewerId(), scopeRepository::userExists, "filters.reviewerId", "reviewer");

        ExportScope.Restriction restriction =
                new ExportScope.Restriction(boundGradeIds, boundClassIds, gradeFilter, classFilter);
        return new ExportScope(restriction.isEmpty() ? null : restriction,
                selectedYearIndex, currentYearIndex, filters.reviewerId());
    }

    /**
     * A filter naming something that does not exist is rejected, not ignored.
     *
     * <p>Matching {@code /reports/*}'s established behaviour for an unknown
     * academic year: silently dropping it would hand back a file that is wider
     * than what was asked for, and the operator has no way to tell from the file
     * that their filter did nothing.</p>
     */
    private void requireExists(String id, java.util.function.Predicate<String> exists, String field, String noun) {
        if (id != null && !exists.test(id)) {
            throw ApiException.notFound("No such " + noun + ": " + id);
        }
    }

    /**
     * Rejects a filter that reaches outside the caller's binding, rather than
     * intersecting it away to an empty file.
     *
     * <p>§4.3 says a filter can only narrow, and an empty result is the literal
     * intersection — but silently returning nothing for "grade 7" when the
     * teacher simply is not bound to grade 7 reads as "grade 7 did nothing".
     * Saying so is both shorter and true. The UI does not offer the choice
     * anyway (§4.3 narrows the dropdown), so reaching this is a hand-made
     * request.</p>
     */
    private String requireWithinBounds(String requested, Set<String> bound, String field, String message) {
        if (requested == null || bound == null) {
            return requested;
        }
        if (!bound.contains(requested)) {
            throw ApiException.validationFailed("Export filters failed validation.",
                    List.of(new FieldErrorDetail(field, "OUT_OF_SCOPE", message)));
        }
        return requested;
    }

    /**
     * {@code null} academic year means "All years" for exports, which is the
     * opposite of {@code /reports/*}, where omitting it means the current year.
     *
     * <p>That difference is deliberate and worth stating where it could be
     * "tidied up": a report is a snapshot of now, while an export is a file
     * someone takes away, and §4.1 makes every export filter default to All so
     * that a download is never quietly narrower than the operator believes. A
     * year that matches no academic year is a 404, matching {@code /reports/*}
     * rather than silently falling back.</p>
     */
    private Integer resolveSelectedYear(String academicYearId, int currentYearIndex) {
        if (academicYearId == null) {
            return null;
        }
        return scopeRepository.academicYearStartYear(academicYearId)
                .orElseThrow(() -> ApiException.notFound("Academic year not found: " + academicYearId));
    }
}
