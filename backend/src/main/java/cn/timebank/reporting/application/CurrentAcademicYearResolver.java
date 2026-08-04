package cn.timebank.reporting.application;

import cn.timebank.student.application.AcademicStructureService;
import cn.timebank.student.application.dto.AcademicYearDto;
import org.springframework.stereotype.Component;

/** Shared by {@link ReportServiceImpl}, {@link DashboardServiceImpl}, and {@link SummaryMaterializationJob} — every report/dashboard in this pass is scoped to the current academic year only (a deliberate scope decision, not a gap: a period selector is future work). */
@Component
class CurrentAcademicYearResolver {

    private final AcademicStructureService academicStructureService;

    CurrentAcademicYearResolver(AcademicStructureService academicStructureService) {
        this.academicStructureService = academicStructureService;
    }

    AcademicYearDto resolve() {
        return academicStructureService.listAcademicYears().stream()
                .filter(AcademicYearDto::current).findFirst()
                .orElseThrow(() -> new IllegalStateException("No academic year is marked current"));
    }
}
