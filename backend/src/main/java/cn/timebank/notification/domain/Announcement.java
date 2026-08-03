package cn.timebank.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Contract §4.1's {@code GET /announcements} — read-only via the API this
 * pass (no create endpoint exists in the contract); rows are populated
 * directly, the same way {@code notification_template} rows are seeded.
 */
@Entity
@Table(name = "announcement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Announcement {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "title_zh", nullable = false, updatable = false)
    private String titleZh;

    @Column(name = "title_en", nullable = false, updatable = false)
    private String titleEn;

    @Column(name = "body_zh", nullable = false, updatable = false)
    private String bodyZh;

    @Column(name = "body_en", nullable = false, updatable = false)
    private String bodyEn;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
