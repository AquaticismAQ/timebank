package cn.timebank.organization.infrastructure;

import cn.timebank.organization.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrganizationRepository extends JpaRepository<Organization, String>, JpaSpecificationExecutor<Organization> {
}
