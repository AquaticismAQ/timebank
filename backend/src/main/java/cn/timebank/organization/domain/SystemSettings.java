package cn.timebank.organization.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * B5.12's `/system/settings` — a single fixed row (seeded, id {@code
 * "default"}), not a generic key-value store: exactly one operational
 * parameter exists today. Distinct from {@link FeatureFlag}, which gates
 * Phase 3/4 surfaces rather than tuning an already-built one.
 */
@Entity
@Table(name = "system_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemSettings extends VersionedEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Setter
    @Column(name = "notification_poll_interval_seconds", nullable = false)
    private int notificationPollIntervalSeconds;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Setter
    @Column(name = "updated_by")
    private String updatedBy;
}
