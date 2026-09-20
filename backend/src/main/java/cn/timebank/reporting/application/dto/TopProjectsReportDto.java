package cn.timebank.reporting.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * `GET /reports/top-projects` (contract §4.13) — highest-participation
 * projects scheduled inside the selected academic year (issue 093 §1; the
 * current year when the caller names none). Before that it was the only
 * report with no period at all: it ranked every project ever run and summed
 * every approved record against them, under a header naming one year.
 */
/*
 * `topProjectsInScope` / `byCategoryInScope` are the same two figures narrowed
 * to the caller's own grades (issue 135). **Both empty for anyone school-wide**:
 * an administrator would otherwise be shown the identical table and pie twice.
 */
public record TopProjectsReportDto(Instant generatedAt, String academicYearId,
                                    List<ProjectFigure> topProjects, List<CategoryFigure> byCategory,
                                    List<ProjectFigure> topProjectsInScope, List<CategoryFigure> byCategoryInScope) {

    public record ProjectFigure(String projectId, String title, long participants, long totalMinutes) {
    }

    /**
     * The same participation, grouped by service category (issue 134).
     *
     * <p>A pie of individual projects was unreadable: ten truncated titles, four
     * of them the same generated name. Categories are the axis someone actually
     * wants — is this school's volunteering mostly campus, community or
     * welfare — and there are six of them, not ten.</p>
     *
     * <p>{@code participants} counts <b>enrolments</b>, not distinct students:
     * a pie is parts of a whole, and distinct-students-per-category would
     * double-count anyone who served in two categories, leaving slices that do
     * not add up to anything. Counted this way the slices sum to exactly the
     * participation the project figures above describe.</p>
     *
     * <p>Carries {@code categoryId}, not a name: the client already holds the
     * category dictionary and renders it in the reader's language, the same
     * division of labour issue 121 settled for everything else.</p>
     */
    public record CategoryFigure(String categoryId, long participants, long totalMinutes) {
    }
}
