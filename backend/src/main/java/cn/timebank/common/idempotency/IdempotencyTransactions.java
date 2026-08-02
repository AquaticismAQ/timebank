package cn.timebank.common.idempotency;

import cn.timebank.common.OpaqueIds;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The {@code REQUIRES_NEW} half of the insert-then-execute flow
 * (Design.md §11), split into its own Spring bean because propagation only
 * takes effect through the proxy — a self-invoked {@code @Transactional}
 * method on the same class silently runs in the caller's transaction.
 *
 * The insert races against concurrent requests carrying the same key on
 * the database's unique constraint, not on application-level locking —
 * that is what makes this safe across two application instances.
 *
 * {@link #insert} deliberately does not catch the constraint violation
 * itself: once a flush fails, Hibernate marks that persistence context
 * rollback-only regardless of whether the exception is caught in Java —
 * a caller that swallows it and keeps using the same transaction hits
 * {@code UnexpectedRollbackException} at commit. Letting it propagate out
 * of this {@code REQUIRES_NEW} method lets Spring roll back cleanly and
 * close the session; {@link IdempotencyService} is what safely catches it,
 * from outside this transaction entirely.
 */
@Component
class IdempotencyTransactions {

    private final IdempotencyRecordRepository repository;

    IdempotencyTransactions(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /** @throws org.springframework.dao.DataIntegrityViolationException if another request already holds this key. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    IdempotencyRecord insert(String idempotencyKey, String principalId, String endpoint, String requestFingerprint) {
        IdempotencyRecord record = new IdempotencyRecord(
                OpaqueIds.generate("idem"), idempotencyKey, principalId, endpoint, requestFingerprint, Instant.now());
        repository.saveAndFlush(record);
        return record;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void markDone(String id, int responseStatus, String responseBodyJson) {
        repository.findById(id).ifPresent(record -> {
            record.setStatus(IdempotencyRecord.Status.DONE);
            record.setResponseStatus(responseStatus);
            record.setResponseBody(responseBodyJson);
            record.setUpdatedAt(Instant.now());
        });
    }

    /** Frees the key after a failed attempt so a retry is not blocked forever (Design.md §11 does not define a FAILED state). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void deleteAfterFailure(String id) {
        repository.deleteById(id);
    }
}
