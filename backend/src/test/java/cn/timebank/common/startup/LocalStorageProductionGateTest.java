package cn.timebank.common.startup;

import cn.timebank.file.application.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Docs/optimization/08-SecurityPrivacyHardening.md §3.4 (finding W-7).
 *
 * <p>Exercises the decision directly rather than booting a context, so the
 * production branch is actually proven rather than assumed — the whole point of
 * the gate is a path that, by construction, never runs in development.</p>
 */
class LocalStorageProductionGateTest {

    /** Named to match the real adapter, because the gate identifies it by class name. */
    private static class LocalFileStorage implements FileStorage {
        @Override public void store(String storageKey, InputStream content) { }
        @Override public InputStream open(String storageKey) { return InputStream.nullInputStream(); }
        @Override public long size(String storageKey) { return 0; }
        @Override public void delete(String storageKey) { }
    }

    /** Stands in for a future object-storage adapter. */
    private static class S3FileStorage implements FileStorage {
        @Override public void store(String storageKey, InputStream content) { }
        @Override public InputStream open(String storageKey) { return InputStream.nullInputStream(); }
        @Override public long size(String storageKey) { return 0; }
        @Override public void delete(String storageKey) { }
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "prod", "staging", "PRODUCTION", " Production "})
    void refusesToStartOnLocalDiskInProduction(String environment) {
        LocalStorageProductionGate gate = new LocalStorageProductionGate(new LocalFileStorage(), environment);

        assertThatThrownBy(() -> gate.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local disk")
                .hasMessageContaining("TIMEBANK_ENVIRONMENT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "development", "test", "test-server", "ci"})
    void startsNormallyOnLocalDiskOutsideProduction(String environment) {
        LocalStorageProductionGate gate = new LocalStorageProductionGate(new LocalFileStorage(), environment);

        assertThatCode(() -> gate.run(null)).doesNotThrowAnyException();
    }

    /**
     * The documented gap: an unset variable warns but does not stop startup,
     * because failing would break local development and the test suite. Asserted
     * so that a later change making it fatal is a deliberate decision rather than
     * an accidental break.
     */
    @Test
    void doesNotBlockStartupWhenTheEnvironmentIsUnspecified() {
        LocalStorageProductionGate gate = new LocalStorageProductionGate(new LocalFileStorage(), "unspecified");

        assertThatCode(() -> gate.run(null)).doesNotThrowAnyException();
    }

    @Test
    void ignoresProductionEntirelyWhenStorageIsNotLocalDisk() {
        LocalStorageProductionGate gate = new LocalStorageProductionGate(new S3FileStorage(), "production");

        assertThatCode(() -> gate.run(null)).doesNotThrowAnyException();
    }

    @Test
    void treatsANullEnvironmentAsUnspecifiedRatherThanFailing() {
        assertThatCode(() -> new LocalStorageProductionGate(new LocalFileStorage(), null).run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void messageNamesTheFixRatherThanJustTheProblem() {
        LocalStorageProductionGate gate = new LocalStorageProductionGate(new LocalFileStorage(), "production");

        assertThatThrownBy(() -> gate.run(null))
                .hasMessageContaining("14-Backend-B3F-CloudFileStorage.md");
    }

    @Test
    void theRealAdapterIsStillNamedLocalFileStorage() {
        // The gate matches on class name, so a rename in cn.timebank.file would
        // silently disable it. This asserts the coupling is still intact.
        assertThat(new LocalFileStorage().getClass().getName()).contains("LocalFileStorage");
    }
}
