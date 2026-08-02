package cn.timebank.common.idempotency;

import cn.timebank.common.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Design.md §11's insert-then-execute flow:
 *
 * <pre>
 * insert (REQUIRES_NEW) with status=IN_PROGRESS, fingerprint = SHA-256(canonical body)
 *   insert succeeds                              -> run the action, store the response, status=DONE
 *   duplicate key, DONE, same fingerprint         -> replay the stored response
 *   duplicate key, DONE, different fingerprint    -> 409 STATE_CONFLICT
 *   duplicate key, IN_PROGRESS                    -> 409 STATE_CONFLICT (already in flight)
 * </pre>
 *
 * Record creation, review decisions, account adjustments, and import
 * confirmation call {@link #execute} with their own idempotent business
 * logic; this class never knows what the action does.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;
    private final IdempotencyTransactions transactions;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository repository, IdempotencyTransactions transactions,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public <T> T execute(String idempotencyKey, String principalId, String endpoint,
                          Object requestBody, Class<T> responseType, Supplier<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.invalidRequest("Idempotency-Key is required for this operation.");
        }
        String fingerprint = fingerprint(requestBody);

        try {
            IdempotencyRecord inserted = transactions.insert(idempotencyKey, principalId, endpoint, fingerprint);
            return runAndStore(inserted, action);
        } catch (DataIntegrityViolationException e) {
            // Another request already holds this key. Safe to catch here — the
            // REQUIRES_NEW transaction that failed has already been rolled back
            // and closed by Spring; this method runs no transaction of its own,
            // so the lookup below opens a clean one.
        }

        // Someone already holds this key — look it up to decide the outcome.
        IdempotencyRecord existing = repository
                .findByIdempotencyKeyAndPrincipalIdAndEndpoint(idempotencyKey, principalId, endpoint)
                .orElseThrow(() -> ApiException.stateConflict("Duplicate request is being processed. Try again shortly."));

        if (existing.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
            throw ApiException.stateConflict("This request is already being processed.");
        }
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw ApiException.stateConflict("This Idempotency-Key was already used with a different request body.");
        }
        return deserialize(existing.getResponseBody(), responseType);
    }

    private <T> T runAndStore(IdempotencyRecord record, Supplier<T> action) {
        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            transactions.deleteAfterFailure(record.getId());
            throw e;
        }
        transactions.markDone(record.getId(), 200, serialize(result));
        return result;
    }

    private String fingerprint(Object requestBody) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(requestBody);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint idempotent request body", e);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize idempotent response", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialize stored idempotent response", e);
        }
    }
}
