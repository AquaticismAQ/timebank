package cn.timebank.dataexchange.application;

import cn.timebank.audit.application.AuditEntry;
import cn.timebank.audit.application.AuditService;
import cn.timebank.common.error.ApiException;
import cn.timebank.dataexchange.application.dto.HistoricalImportWindowDto;
import cn.timebank.dataexchange.domain.HistoricalImportWindow;
import cn.timebank.dataexchange.infrastructure.HistoricalImportWindowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
class HistoricalImportWindowServiceImpl implements HistoricalImportWindowService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalImportWindowServiceImpl.class);

    /** Design/*&#47;11-DataImport.md §6.1. Configuration may shorten or lengthen it; it may not remove it. */
    static final int DEFAULT_WINDOW_DAYS = 15;

    /**
     * The ceiling that keeps the property from being a way to delete the
     * mechanism (Q-9). Configurable at all because a migration that genuinely
     * runs long should not need a code change and a deploy at the worst
     * possible moment; capped because {@code 3650} would leave the door open
     * for a decade while still looking like a window. Hardcoded rather than
     * configurable — a configurable ceiling is not a ceiling.
     */
    static final int MAX_WINDOW_DAYS = 60;

    private final HistoricalImportWindowRepository repository;
    private final AuditService auditService;
    private final int windowDays;

    HistoricalImportWindowServiceImpl(HistoricalImportWindowRepository repository, AuditService auditService,
                                       @Value("${timebank.import.historical-window-days:15}") int configuredWindowDays) {
        this.repository = repository;
        this.auditService = auditService;
        this.windowDays = effectiveWindowDays(configuredWindowDays);
    }

    /**
     * Clamped rather than rejected at startup: a nonsensical window length is
     * not a reason for the whole application to refuse to boot, and the log
     * line says plainly what was asked for and what is in force. Zero and
     * negatives collapse to one day rather than to "already closed", which
     * would silently close a feature nobody intended to close.
     */
    static int effectiveWindowDays(int configured) {
        if (configured >= 1 && configured <= MAX_WINDOW_DAYS) {
            return configured;
        }
        int clamped = configured < 1 ? 1 : MAX_WINDOW_DAYS;
        log.warn("timebank.import.historical-window-days={} is outside 1..{} — using {} days instead"
                + " (the design's length is {}; see 11-DataImport.md §6.1)",
                configured, MAX_WINDOW_DAYS, clamped, DEFAULT_WINDOW_DAYS);
        return clamped;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireImportable(String importType) {
        expired(importType).ifPresent(window -> {
            throw ApiException.stateConflict("Historical import closed on " + expiryText(window)
                    + ", when the " + window.getWindowDays() + "-day window opened by the first historical import ran out."
                    + " " + REOPEN_SENTENCE
                    + " Roster import is unaffected and remains available.");
        });
    }

    @Override
    @Transactional(readOnly = true)
    public void requireCommittable(String importType) {
        expired(importType).ifPresent(window -> {
            throw ApiException.stateConflict("This import was validated while the historical-import window was open,"
                    + " but the window closed on " + expiryText(window) + ", so it can no longer be committed."
                    + " " + REOPEN_SENTENCE);
        });
    }

    /**
     * Named in both refusals, because an operator who reads "closed" and is
     * given no route back will look for one in the interface, where there is
     * none by design (Docs/backlog/01 §2).
     */
    private static final String REOPEN_SENTENCE =
            "No request can reopen it: a system administrator must run the historical-import re-enable utility"
                    + " on the server console.";

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void openOnFirstCommit(String jobId, String actorId) {
        Instant openedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = openedAt.plus(Duration.ofDays(windowDays));
        int opened = repository.openIfAbsent(HistoricalImportWindow.SINGLETON_ID, openedAt, expiresAt,
                windowDays, jobId, actorId);
        if (opened == 0) {
            // Already open, or already expired. Either way this import ran
            // inside a window somebody else started, and §6.1 is explicit that
            // it does not get to move the expiry.
            log.info("Historical import job {} committed inside the existing window — expiry unchanged", jobId);
            return;
        }
        auditService.record(new AuditEntry(actorId, "imports.commit", "import.historicalWindowOpened",
                "historicalImportWindow", HistoricalImportWindow.SINGLETON_ID, null,
                new WindowOpened(jobId, windowDays, expiresAt), null));
        log.info("First historical import (job {}) opened the {}-day import window; it closes at {}",
                jobId, windowDays, expiresAt);
    }

    /** The audit row's {@code after} summary — the three facts someone reconstructing this afterwards needs. */
    private record WindowOpened(String openedByJobId, int windowDays, Instant expiresAt) {
    }

    @Override
    @Transactional(readOnly = true)
    public HistoricalImportWindowDto state() {
        Optional<HistoricalImportWindow> window = repository.findById(HistoricalImportWindow.SINGLETON_ID);
        if (window.isEmpty()) {
            return HistoricalImportWindowDto.notOpened(windowDays);
        }
        HistoricalImportWindow w = window.get();
        Instant now = Instant.now();
        if (w.hasExpiredAt(now)) {
            return new HistoricalImportWindowDto(HistoricalImportWindowDto.EXPIRED, w.getOpenedAt(), w.getExpiresAt(),
                    w.getWindowDays(), null);
        }
        return new HistoricalImportWindowDto(HistoricalImportWindowDto.OPEN, w.getOpenedAt(), w.getExpiresAt(),
                w.getWindowDays(), Duration.between(now, w.getExpiresAt()).toSeconds());
    }

    /** The window, but only when it exists, has expired, and this type is the one it governs. */
    private Optional<HistoricalImportWindow> expired(String importType) {
        if (!HistoricalImportRowValidator.TYPE.equals(importType)) {
            return Optional.empty();
        }
        return repository.findById(HistoricalImportWindow.SINGLETON_ID)
                .filter(window -> window.hasExpiredAt(Instant.now()));
    }

    /** UTC and to the second: an unambiguous instant in an operator-facing message beats a friendly one that could be read an offset out. */
    private static String expiryText(HistoricalImportWindow window) {
        return window.getExpiresAt().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
