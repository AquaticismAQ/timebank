package cn.timebank.dataexchange.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q-9: the window length is configurable so that a migration which genuinely
 * runs long does not need a code change and a deploy at the worst possible
 * moment — but a configurable length is also how the mechanism gets deleted by
 * accident. {@code 3650} would look like a window and behave like an open door
 * for a decade.
 *
 * <p>These cases are the ceiling, not decoration: without the clamp the first
 * two below would return exactly what was configured.</p>
 */
class HistoricalImportWindowLengthTest {

    @Test
    void anAbsurdlyLongWindowIsCappedRatherThanHonoured() {
        assertThat(HistoricalImportWindowServiceImpl.effectiveWindowDays(3650))
                .isEqualTo(HistoricalImportWindowServiceImpl.MAX_WINDOW_DAYS);
        assertThat(HistoricalImportWindowServiceImpl.effectiveWindowDays(61))
                .isEqualTo(HistoricalImportWindowServiceImpl.MAX_WINDOW_DAYS);
    }

    /**
     * Zero or negative collapses to one day, not to "already closed". A
     * mistyped property should shorten the window, never silently shut a
     * feature nobody meant to shut.
     */
    @Test
    void zeroOrNegativeBecomesTheShortestRealWindow() {
        assertThat(HistoricalImportWindowServiceImpl.effectiveWindowDays(0)).isEqualTo(1);
        assertThat(HistoricalImportWindowServiceImpl.effectiveWindowDays(-5)).isEqualTo(1);
    }

    @Test
    void theDesignsFifteenDaysAndAnythingElseInRangeIsUsedAsGiven() {
        assertThat(HistoricalImportWindowServiceImpl.effectiveWindowDays(15)).isEqualTo(15);
        assertThat(HistoricalImportWindowServiceImpl.DEFAULT_WINDOW_DAYS).isEqualTo(15);
        assertThat(HistoricalImportWindowServiceImpl.effectiveWindowDays(1)).isEqualTo(1);
        assertThat(HistoricalImportWindowServiceImpl.effectiveWindowDays(
                HistoricalImportWindowServiceImpl.MAX_WINDOW_DAYS))
                .isEqualTo(HistoricalImportWindowServiceImpl.MAX_WINDOW_DAYS);
    }
}
