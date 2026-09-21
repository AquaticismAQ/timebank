package cn.timebank.dataexchange.infrastructure;

import cn.timebank.dataexchange.domain.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Plain {@link JpaRepository}, not append-only (Job rows are mutated in place as a run progresses) — the B5.1 lesson about JpaSpecificationExecutor doesn't apply here since mutation is exactly what this table is for. */
public interface JobRepository extends JpaRepository<Job, String> {

    Optional<Job> findBySourceFileHashAndStatus(String sourceFileHash, String status);

    /** B5.11's reclaim sweep: any job whose lease has expired while still claimed as running. */
    List<Job> findByStatusInAndLeaseExpiresAtBefore(List<String> statuses, Instant cutoff);

    /**
     * Marks an import commit COMMITTED, from inside the transaction that
     * applied its rows (issue 146).
     *
     * <p>Two things this is not. It is not {@code JobProgressWriter.succeed},
     * because that runs {@code REQUIRES_NEW} — correct for an export, wrong
     * here, where the status and the rows must land together or a crash
     * between them leaves applied rows under an unfinished-looking job.</p>
     *
     * <p>And it is conditional on the status still being {@code COMMITTING}:
     * if another run has reclaimed or retried this job in the meantime, this
     * one returns 0 and its caller rolls back rather than both runs believing
     * they finished it.</p>
     *
     * @return 1 if this run still owned the job, 0 if it did not
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Job j
               set j.status = 'COMMITTED', j.validRows = :committed, j.processedRows = :committed,
                   j.finishedAt = :now, j.updatedAt = :now
             where j.id = :jobId and j.status = 'COMMITTING'
            """)
    int finishCommitIfStillCommitting(@Param("jobId") String jobId, @Param("committed") int committed,
                                       @Param("now") Instant now);
}
