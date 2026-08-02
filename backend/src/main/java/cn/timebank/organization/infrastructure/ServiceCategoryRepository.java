package cn.timebank.organization.infrastructure;

import cn.timebank.organization.domain.ServiceCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServiceCategoryRepository extends JpaRepository<ServiceCategory, String> {

    Optional<ServiceCategory> findByCode(String code);
}
