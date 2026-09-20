package cn.timebank.student.application;

import cn.timebank.common.error.ApiException;
import cn.timebank.student.application.dto.AcademicYearDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Turns an optional {@code academicYearId} filter into the instant window a
 * list query is bounded by.
 *
 * <p>Extracted when a third list endpoint needed this (issue 115: cases and
 * proposals, after projects in issue 112). Three copies of "absent means every
 * year, unknown is a 404, and the window comes from the stored row" is three
 * chances for one of them to drift into answering a bad id with a good-looking
 * page.</p>
 *
 * <p><b>The window is read from the {@code academic_year} row</b>, never
 * recomputed from the turnover date. That date is configurable
 * (`TIMEBANK_ACADEMIC_YEAR_START`) and an existing year keeps the dates it was
 * created with, so arithmetic here would drift the moment either changed.</p>
 *
 * <p>Deliberately <b>not</b> merged with {@code ReportAcademicYearResolver},
 * which reads an absent parameter as <em>the current year</em>. That is right
 * for a report — a snapshot of now — and wrong for a general-purpose list
 * several screens and any future caller share, where narrowing on a missing
 * parameter changes behaviour nobody asked to change. Two rules, deliberately,
 * each stated where it applies.</p>
 */
@Component
public class AcademicYearWindowResolver {

    /**
     * Half-open, so a row timestamped at any moment on the year's last day is
     * inside it and no row can fall in two years or in neither.
     */
    public record Window(Instant fromInclusive, Instant untilExclusive) {}

    private final AcademicStructureService academicStructureService;

    AcademicYearWindowResolver(AcademicStructureService academicStructureService) {
        this.academicStructureService = academicStructureService;
    }

    /**
     * @param academicYearId absent or blank for "every year"
     * @return {@code null} when no year was asked for — the caller adds no predicate
     * @throws ApiException 404 when the id names no year. Never a silent fall back
     *         to the current year: a caller asking for a year that does not exist
     *         has a bug, and answering with another year's rows hides it behind
     *         data that looks real.
     */
    public Window resolveOrNull(String academicYearId) {
        if (academicYearId == null || academicYearId.isBlank()) {
            return null;
        }
        String wanted = academicYearId.trim();
        AcademicYearDto year = academicStructureService.listAcademicYears().stream()
                .filter(y -> y.id().equals(wanted)).findFirst()
                .orElseThrow(() -> ApiException.notFound("No academic year with id " + wanted));
        return windowOf(year);
    }

    /**
     * The year flagged current, or {@code null} if none is.
     *
     * <p>Null rather than an exception: no current year is a seeding fault, and
     * failing every list because of it would turn a misconfiguration into an
     * outage.</p>
     */
    public Window currentOrNull() {
        return academicStructureService.listAcademicYears().stream()
                .filter(AcademicYearDto::current).findFirst()
                .map(AcademicYearWindowResolver::windowOf)
                .orElse(null);
    }

    private static Window windowOf(AcademicYearDto year) {
        return new Window(
                year.startDate().atStartOfDay(ZoneId.systemDefault()).toInstant(),
                // Start of the day after the last day, so the bound stays exclusive
                // while still including everything that happened on that last day.
                year.endDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
