package cn.timebank.organization.infrastructure;

import cn.timebank.organization.domain.SchoolInfo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolInfoRepository extends JpaRepository<SchoolInfo, String> {
}
