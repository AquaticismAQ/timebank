package cn.timebank.reporting.application;

import cn.timebank.reporting.application.dto.CompletionReportDto;
import cn.timebank.reporting.application.dto.ParticipationReportDto;
import cn.timebank.reporting.application.dto.ReviewEfficiencyReportDto;
import cn.timebank.reporting.application.dto.ServiceHoursReportDto;
import cn.timebank.reporting.application.dto.TopProjectsReportDto;

/**
 * Contract §4.13, `reports.read`-gated. Participation/completion read the
 * B5.8 materialized summary when one exists, falling back to a live
 * computation otherwise — never a 500 before the first scheduled tick.
 *
 * <p>Every method takes an optional {@code academicYearId} (issue 093 §1).
 * {@code null}/blank means the current academic year; an id that matches no
 * academic year is a 404, not a silent widening to every year. What the
 * scope <em>means</em> differs per report and is documented on each method —
 * they are not all "rows tagged with this year", because nothing in the
 * schema tags a service record or a student with an academic year.</p>
 */
public interface ReportService {

    /**
     * Denominator: students {@code ACTIVE} now. Numerator: those of them with
     * a non-draft service record <em>dated inside</em> the selected year.
     */
    ParticipationReportDto participation(String resolvedLanguage, String academicYearId);

    /**
     * As {@link #participation} for the population, but the numerator is
     * students whose approved minutes dated inside the selected year meet
     * that year's annual requirement for their grade band.
     */
    CompletionReportDto completion(String resolvedLanguage, String academicYearId);

    /** Approved minutes bucketed by month, over service dates inside the selected year. */
    ServiceHoursReportDto serviceHours(String academicYearId);

    /**
     * Review decisions <em>taken</em> inside the selected year — a decision
     * is attributed to when the reviewer made it, not to when the service it
     * judged happened.
     */
    ReviewEfficiencyReportDto reviewEfficiency(String academicYearId);

    /** Projects <em>scheduled</em> inside the selected year, with their hours from records dated inside it. */
    TopProjectsReportDto topProjects(String academicYearId);
}
