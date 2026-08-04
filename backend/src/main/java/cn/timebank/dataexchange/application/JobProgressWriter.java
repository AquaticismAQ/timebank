package cn.timebank.dataexchange.application;

import cn.timebank.common.error.ApiException;
import cn.timebank.dataexchange.domain.Job;
import cn.timebank.dataexchange.infrastructure.JobRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Every DB touch a background run makes goes through here, each its own
 * short {@code REQUIRES_NEW} transaction — a claim, a progress update, or a
 * terminal state must commit and become visible immediately (to the
 * reclaim sweep, to a polling client), not wait for the whole multi-minute
 * run to finish (same reasoning as B0's {@code IdempotencyTransactions}).
 * A separate bean, not a method on the runner itself, so the runner's own
 * {@code @Async} call reaches this through the Spring proxy rather than a
 * self-invocation that would silently skip transactional advice.
 */
@Component
public class JobProgressWriter {

    private static final int LEASE_SECONDS = 120;

    private final JobRepository jobRepository;

    public JobProgressWriter(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /** @return the claimed job, or empty if it was already claimed/finished by another run (race guard). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Job claim(String jobId, String runningStatus) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
        Instant now = Instant.now();
        job.setStatus(runningStatus);
        job.setClaimedBy(hostname());
        job.setClaimedAt(now);
        job.setLeaseExpiresAt(now.plusSeconds(LEASE_SECONDS));
        if (job.getStartedAt() == null) {
            job.setStartedAt(now);
        }
        job.setUpdatedAt(now);
        return jobRepository.saveAndFlush(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void renewLeaseAndProgress(String jobId, int processedRows, int checkpointOffset) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
        job.setProcessedRows(processedRows);
        job.setCheckpointOffset(checkpointOffset);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(LEASE_SECONDS));
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
    }

    /** Import commit's resume-critical checkpoint: {@code checkpointOffset} (raw lines processed) and {@code validRows} (cumulative committed count) travel together so a reclaimed run resumes from exactly where the last one stopped, not from an inconsistent mix of the two. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void renewCommitProgress(String jobId, int processedRows, int checkpointOffset, int committedSoFar) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
        job.setProcessedRows(processedRows);
        job.setCheckpointOffset(checkpointOffset);
        job.setValidRows(committedSoFar);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(LEASE_SECONDS));
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeValidation(String jobId, String status, String errorRef, int totalRows, int validRows) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
        job.setStatus(status);
        job.setErrorRef(errorRef);
        job.setTotalRows(totalRows);
        job.setValidRows(validRows);
        job.setCheckpointOffset(0);
        job.setFinishedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(String jobId, String terminalStatus, String resultRef, Integer totalRows, Integer validRows) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
        job.setStatus(terminalStatus);
        job.setResultRef(resultRef);
        if (totalRows != null) job.setTotalRows(totalRows);
        if (validRows != null) job.setValidRows(validRows);
        job.setFinishedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String jobId, String terminalStatus, String errorMessage) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("Job not found."));
        job.setStatus(terminalStatus);
        job.setErrorMessage(errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage);
        job.setFinishedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
