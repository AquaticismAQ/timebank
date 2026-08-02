package cn.timebank.iam.infrastructure;

import cn.timebank.iam.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, String> {

    Optional<Role> findByCode(String code);
}
