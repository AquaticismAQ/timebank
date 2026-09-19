package cn.timebank.dataexchange.application.dto;

import java.time.Instant;

/**
 * What the import screen needs to choose between §6.2's three states:
 * warn before the first import, count down during the window, and show
 * nothing at all once it has closed (Design/*&#47;11-DataImport.md §6.1 —
 * "a control you cannot use should not be visible").
 *
 * <p>{@code secondsRemaining} travels alongside {@code expiresAt} rather than
 * being left to the browser to subtract, because the two answer different
 * questions and one of them must not depend on the client's clock: §6.2 wants
 * a countdown *and* a date, the countdown because it says how long is left and
 * the date because that is what goes into a migration plan. A device with a
 * skewed clock would otherwise show a confident countdown that is wrong.</p>
 *
 * @param state             {@code NOT_OPENED}, {@code OPEN} or {@code EXPIRED}
 * @param openedAt          when the first historical import committed; null unless opened
 * @param expiresAt         when historical import closes for good; null unless opened
 * @param windowDays        the length that was applied, as stored on the row
 * @param secondsRemaining  time left, never negative; null unless {@code OPEN}
 */
public record HistoricalImportWindowDto(String state, Instant openedAt, Instant expiresAt,
                                         Integer windowDays, Long secondsRemaining) {

    public static final String NOT_OPENED = "NOT_OPENED";
    public static final String OPEN = "OPEN";
    public static final String EXPIRED = "EXPIRED";

    public static HistoricalImportWindowDto notOpened(int configuredWindowDays) {
        return new HistoricalImportWindowDto(NOT_OPENED, null, null, configuredWindowDays, null);
    }
}
