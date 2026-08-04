package cn.timebank.dataexchange.infrastructure;

import cn.timebank.dataexchange.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Plain {@link JpaRepository}, not append-only (Job rows are mutated in place as a run progresses) — the B5.1 lesson about JpaSpecificationExecutor doesn't apply here since mutation is exactly what this table is for. */
public interface JobRepository extends JpaRepository<Job, String> {

    Optional<Job> findBySourceFileHashAndStatus(String sourceFileHash, String status);

    /** B5.11's reclaim sweep: any job whose lease has expired while still claimed as running. */
    List<Job> findByStatusInAndLeaseExpiresAtBefore(List<String> statuses, Instant cutoff);
}
