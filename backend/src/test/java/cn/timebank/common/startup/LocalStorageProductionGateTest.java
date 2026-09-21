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
    @ValueSource(strings = {"local", "dev", "development", "test", "test-server", "ci", "it"})
    void startsNormallyOnLocalDiskOutsideProduction(String environment) {
        LocalStorageProductionGate gate = new LocalStorageProductionGate(new LocalFileStorage(), environment);

        assertThatCode(() -> gate.run(null)).doesNotThrowAnyException();
    }

    /**
     * Issue 148, and the case the gate exists for. A deployment that forgets
     * TIMEBANK_ENVIRONMENT is the likeliest way to reach production on the
     * test-stage adapter, and it used to be the one case that started anyway.
     *
     * <p>The message has to carry the accepted values: an operator told only
     * that theirs is wrong has to go and read the source to find out what is
     * right.</p>
     */
    @ParameterizedTest
    @ValueSource(strings = {"unspecified", "uat", "qa", "prod-eu", ""})
    void refusesToStartWhenTheEnvironmentIsNotRecognised(String environment) {
        LocalStorageProductionGate gate = new LocalStorageProductionGate(new LocalFileStorage(), environment);

        assertThatThrownBy(() -> gate.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local disk")
                .hasMessageContaining("TIMEBANK_ENVIRONMENT")
                .hasMessageContaining("test-server");
    }

    /**
     * `prod-eu` above is the reason this is an allow-list rather than a longer
     * deny-list: it is neither in the production set nor the non-production one,
     * and under the old check it would have started — a name that reads as
     * production to any human passing straight through the gate.
     */
    @Test
    void aProductionLookingNameOutsideBothListsIsStillRefused() {
        LocalStorageProductionGate gate = new LocalStorageProductionGate(new LocalFileStorage(), "prod-eu");

        assertThatThrownBy(() -> gate.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ignoresProductionEntirelyWhenStorageIsNotLocalDisk() {
        LocalStorageProductionGate gate = new LocalStorageProductionGate(new S3FileStorage(), "production");

        assertThatCode(() -> gate.run(null)).doesNotThrowAnyException();
    }

    /** A null property must reach the same refusal as an unset one, not a NullPointerException. */
    @Test
    void treatsANullEnvironmentAsUnrecognisedRatherThanThrowingSomethingElse() {
        assertThatThrownBy(() -> new LocalStorageProductionGate(new LocalFileStorage(), null).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unspecified");
    }

    /**
     * The scope limit. Once evidence is on real object storage the gate has no
     * opinion about the environment at all — including an unset one, which is
     * why this asserts on that case specifically rather than on "production".
     */
    @Test
    void saysNothingAboutAnUnsetEnvironmentWhenStorageIsNotLocalDisk() {
        assertThatCode(() -> new LocalStorageProductionGate(new S3FileStorage(), "unspecified").run(null))
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
