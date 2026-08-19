package cn.timebank.iam.infrastructure;

import cn.timebank.iam.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, String> {

    Optional<Role> findByCode(String code);

    /** 07-RoleFinetune.md §2.7: never resolve a user's role to a template row, even if its code is guessed. */
    Optional<Role> findByCodeAndIsTemplateFalse(String code);

    List<Role> findByIsTemplate(boolean isTemplate);
}
