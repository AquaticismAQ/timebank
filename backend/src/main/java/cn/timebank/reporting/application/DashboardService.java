package cn.timebank.reporting.application;

import cn.timebank.reporting.application.dto.DashboardAdminDto;
import cn.timebank.reporting.application.dto.DashboardStudentDto;
import cn.timebank.reporting.application.dto.DashboardTeacherDto;
import org.springframework.security.core.Authentication;

/**
 * Contract §4.13/§12.4: home-page aggregation only, viewer-derived — every
 * implementation caches per-principal, never across principals (Design.md
 * §16.2, the phase doc's own named privacy risk).
 */
public interface DashboardService {

    DashboardStudentDto student(Authentication auth, String resolvedLanguage);

    DashboardTeacherDto teacher(Authentication auth, String resolvedLanguage);

    DashboardAdminDto admin(Authentication auth, String resolvedLanguage);
}
