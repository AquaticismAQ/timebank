package cn.timebank.common.idempotency;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiErrorCode;
import cn.timebank.common.error.ApiException;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B0.7 / B0's exit criteria: replay, fingerprint conflict, and in-flight
 * conflict, each proven against real MySQL — Design.md §11's flow only
 * matters if the unique constraint it depends on actually holds under a
 * real database, not a mock repository.
 */
class IdempotencyServiceIT extends AbstractIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRecordRepository repository;

    record DemoRequest(String field) {
    }

    @Test
    void secondCallWithSameKeyAndBodyReplaysWithoutRerunningTheAction() {
        String key = OpaqueIds.generate("test-key");
        DemoRequest body = new DemoRequest("value-1");
        AtomicInteger invocations = new AtomicInteger();

        String first = idempotencyService.execute(key, "usr_a", "POST /demo", body, String.class,
                () -> "result-" + invocations.incrementAndGet());
        String second = idempotencyService.execute(key, "usr_a", "POST /demo", body, String.class,
                () -> "result-" + invocations.incrementAndGet());

        assertThat(first).isEqualTo("result-1");
        assertThat(second).isEqualTo("result-1"); // replayed, not "result-2"
        assertThat(invocations.get()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsAStateConflict() {
        String key = OpaqueIds.generate("test-key");
        idempotencyService.execute(key, "usr_b", "POST /demo", new DemoRequest("value-1"), String.class, () -> "ok");

        assertThatThrownBy(() -> idempotencyService.execute(
                key, "usr_b", "POST /demo", new DemoRequest("value-DIFFERENT"), String.class, () -> "ok"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ApiErrorCode.STATE_CONFLICT));
    }

    @Test
    void aKeyStillInProgressIsAStateConflict() {
        String key = OpaqueIds.generate("test-key");
        // Simulates a concurrent request that has claimed the key but not finished —
        // the row IdempotencyTransactions.tryInsert would have created, without the
        // completion IdempotencyTransactions.markDone would have applied.
        repository.save(new IdempotencyRecord(
                OpaqueIds.generate("idem"), key, "usr_c", "POST /demo", "irrelevant-fingerprint", Instant.now()));

        assertThatThrownBy(() -> idempotencyService.execute(
                key, "usr_c", "POST /demo", new DemoRequest("value-1"), String.class, () -> "ok"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ApiErrorCode.STATE_CONFLICT));
    }

    @Test
    void aFailedActionFreesTheKeyForRetry() {
        String key = OpaqueIds.generate("test-key");
        DemoRequest body = new DemoRequest("value-1");

        assertThatThrownBy(() -> idempotencyService.execute(key, "usr_d", "POST /demo", body, String.class, () -> {
            throw new IllegalStateException("simulated failure");
        })).isInstanceOf(IllegalStateException.class);

        // Retry with the same key succeeds rather than being permanently blocked.
        String result = idempotencyService.execute(key, "usr_d", "POST /demo", body, String.class, () -> "recovered");
        assertThat(result).isEqualTo("recovered");
    }
}
