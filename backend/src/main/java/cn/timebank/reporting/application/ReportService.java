package cn.timebank.reporting.application;

import cn.timebank.reporting.application.dto.CompletionReportDto;
import cn.timebank.reporting.application.dto.ParticipationReportDto;
import cn.timebank.reporting.application.dto.ReviewEfficiencyReportDto;
import cn.timebank.reporting.application.dto.ServiceHoursReportDto;
import cn.timebank.reporting.application.dto.TopProjectsReportDto;

/** Contract §4.13, `reports.read`-gated. Participation/completion read the B5.8 materialized summary when one exists, falling back to a live computation otherwise — never a 500 before the first scheduled tick. */
public interface ReportService {

    ParticipationReportDto participation(String resolvedLanguage);

    CompletionReportDto completion(String resolvedLanguage);

    ServiceHoursReportDto serviceHours();

    ReviewEfficiencyReportDto reviewEfficiency();

    TopProjectsReportDto topProjects();
}
