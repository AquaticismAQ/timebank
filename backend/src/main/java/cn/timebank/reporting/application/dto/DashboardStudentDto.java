package cn.timebank.reporting.application.dto;

import cn.timebank.account.application.dto.AccountDto;
import cn.timebank.progress.application.dto.ProgressDto;
import cn.timebank.project.application.dto.ProjectDto;
import cn.timebank.servicerecord.application.dto.ServiceRecordDto;

import java.time.Instant;
import java.util.List;

/** `GET /dashboards/student` (contract §4.13) — home-page aggregation only; detail pages still use the resource endpoints (§12.4). */
public record DashboardStudentDto(Instant generatedAt, ProgressDto progress, AccountDto account,
                                   List<ServiceRecordDto> recentRecords, List<ProjectDto> upcomingProjects,
                                   long unreadNotificationCount) {
}
