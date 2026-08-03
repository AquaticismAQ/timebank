package cn.timebank.organization.infrastructure;

import cn.timebank.organization.domain.SystemSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemSettingsRepository extends JpaRepository<SystemSettings, String> {
}
