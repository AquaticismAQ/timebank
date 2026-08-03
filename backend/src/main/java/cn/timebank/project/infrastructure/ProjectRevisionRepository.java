package cn.timebank.project.infrastructure;

import cn.timebank.project.domain.ProjectRevision;
import org.springframework.data.repository.Repository;

import java.util.List;

/** Append-only — bare {@link Repository} marker, same structural enforcement as `AuditLogRepository`. */
public interface ProjectRevisionRepository extends Repository<ProjectRevision, String> {

    ProjectRevision save(ProjectRevision revision);

    List<ProjectRevision> findByProjectIdOrderByRevisionNumberAsc(String projectId);
}
