package cn.timebank.dataexchange.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The window opened by the first committed historical import
 * (Design/*&#47;12-DataImport.md §6). Exactly one row exists or none does:
 * "no row" means no historical import has ever committed, so the feature is
 * still available and the interface owes the operator §6.2's warning.
 *
 * <p>Deliberately read-only from Java. The single write — opening the window
 * — goes through the repository's insert-if-absent statement rather than a
 * setter, because "later imports do not extend it" (§6.1) has to hold even
 * for two commits racing, and a load-check-save would not guarantee that.
 * The {@code reopened*} columns belong to the unbuilt console utility
 * (Docs/backlog/01-HistoricalImportReenableUtility.md); they are exposed
 * here so a reader can see the full shape, not because anything in this
 * phase writes them.</p>
 */
@Entity
@Table(name = "historical_import_window")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HistoricalImportWindow {

    /**
     * The window is a property of the import feature, of which there is one,
     * so its row has a fixed id rather than a generated one. An opaque
     * generated id would let a second row exist, which is precisely the state
     * this table must not be able to reach.
     */
    public static final String SINGLETON_ID = "hiw_singleton";

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "window_days", nullable = false)
    private int windowDays;

    @Column(name = "opened_by_job_id", length = 32, nullable = false)
    private String openedByJobId;

    @Column(name = "opened_by", length = 32)
    private String openedBy;

    @Column(name = "reopened_at")
    private Instant reopenedAt;

    @Column(name = "reopened_by", length = 64)
    private String reopenedBy;

    @Column(name = "reopen_reason", length = 500)
    private String reopenReason;

    public boolean hasExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
