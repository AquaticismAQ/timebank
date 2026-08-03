package cn.timebank.progress.application;

import cn.timebank.progress.application.dto.ProgressDto;
import org.springframework.security.core.Authentication;

/** The `progress` module's published interface (Design.md §6). */
public interface ProgressService {

    /** `GET /students/{id}/progress` — self, or any staff role holding `progress.read` (contract §4.9). */
    ProgressDto getProgress(String studentId, Authentication auth);

    /**
     * B3.11/B3.12: called only from inside {@code ReviewServiceImpl}'s
     * approval transaction, with amounts already resolved against the rule
     * in effect at the record's own service date (B3.13) — this method
     * never re-derives against "the current rule" for the lifetime totals,
     * which is what makes invariant 8 hold. {@code honourLevel} is the one
     * value evaluated against the *current* rule, since it is a live status
     * label rather than a historical fact about a specific record.
     */
    void addCredit(String studentId, int creditedMinutes, int creditedCentiCoins);
}
