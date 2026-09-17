package cn.timebank.reporting.application;

import cn.timebank.common.error.ApiException;
import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.dto.AcademicYearDto;
import org.springframework.stereotype.Component;

/**
 * Turns the optional {@code academicYearId} query parameter of contract
 * §4.13's report endpoints into the {@link AcademicYearDto} whose date
 * window every report query is then bounded by.
 *
 * <p>Was {@code CurrentAcademicYearResolver}, whose Javadoc said "every
 * report/dashboard in this pass is scoped to the current academic year only
 * … a period selector is future work". Issue 093 §1 is that future work:
 * the Reports page has always had a year dropdown, and it did nothing
 * because no report endpoint accepted a year. {@link #resolve()} (no
 * argument) keeps the old meaning and is still what
 * {@code DashboardServiceImpl} and {@code SummaryMaterializationJob} call —
 * neither takes a period from the caller.</p>
 *
 * <p>Two deliberate choices about absent/unknown input:</p>
 * <ul>
 *   <li><b>Absent or blank → the current year</b>, never "all years". A
 *       report that silently widens to every year on a missing parameter is
 *       the failure mode this change exists to remove.</li>
 *   <li><b>Unknown id → 404</b>, never a silent fall back to the current
 *       year. A caller that asks for a year that does not exist has a bug,
 *       and answering it with a different year's figures hides that bug
 *       behind numbers that look real.</li>
 * </ul>
 */
@Component
class ReportAcademicYearResolver {

    private final AcademicStructureService academicStructureService;

    ReportAcademicYearResolver(AcademicStructureService academicStructureService) {
        this.academicStructureService = academicStructureService;
    }

    /** The year flagged {@code is_current} — for callers that have no period of their own. */
    AcademicYearDto resolve() {
        return academicStructureService.listAcademicYears().stream()
                .filter(AcademicYearDto::current).findFirst()
                .orElseThrow(() -> new IllegalStateException("No academic year is marked current"));
    }

    /** The requested year, or the current one when {@code academicYearId} is absent/blank. */
    AcademicYearDto resolve(String academicYearId) {
        if (academicYearId == null || academicYearId.isBlank()) {
            return resolve();
        }
        String wanted = academicYearId.trim();
        return academicStructureService.listAcademicYears().stream()
                .filter(y -> y.id().equals(wanted)).findFirst()
                .orElseThrow(() -> ApiException.notFound("No academic year with id " + wanted));
    }
}
