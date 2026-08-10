package cn.timebank.project.infrastructure;

import cn.timebank.project.domain.ProjectOrganization;
import cn.timebank.project.domain.ProjectOrganizationRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectOrganizationRepository extends JpaRepository<ProjectOrganization, String> {

    List<ProjectOrganization> findByProjectIdOrderBySortOrderAsc(String projectId);

    List<ProjectOrganization> findByProjectIdInOrderBySortOrderAsc(List<String> projectIds);

    Optional<ProjectOrganization> findByProjectIdAndRole(String projectId, ProjectOrganizationRole role);

    void deleteByProjectIdAndRole(String projectId, ProjectOrganizationRole role);
}
