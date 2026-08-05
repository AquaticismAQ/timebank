package cn.timebank.notification.domain;

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
 * B3.12's fifth approval effect needs somewhere to write to — B2 built only
 * template rendering ("the notification row itself... is B5's"). I3.6:
 * title/body are rendered on every read from {@code templateCode} + the
 * reader's resolved language, not stored — {@code NotificationTemplate}'s
 * own rendering has never taken interpolation params, so the template code
 * alone is enough to reconstruct either language's text at any time. This
 * used to store a creation-time snapshot instead (V5), explicitly flagged
 * in this class's own prior doc comment as deferred "since re-rendering
 * per request would need the recipient's language resolved at read time,
 * which no read endpoint exists to do yet" — B5/I1 built that endpoint
 * since, closing the gap (V12 drops the now-wrong stored columns).
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "recipient_user_id", length = 32, nullable = false, updatable = false)
    private String recipientUserId;

    @Column(name = "template_code", length = 64, nullable = false, updatable = false)
    private String templateCode;

    @Column(name = "related_resource_type", updatable = false)
    private String relatedResourceType;

    @Column(name = "related_resource_id", updatable = false)
    private String relatedResourceId;

    @Setter
    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Notification(String id, String recipientUserId, String templateCode,
                         String relatedResourceType, String relatedResourceId, Instant now) {
        this.id = id;
        this.recipientUserId = recipientUserId;
        this.templateCode = templateCode;
        this.relatedResourceType = relatedResourceType;
        this.relatedResourceId = relatedResourceId;
        this.read = false;
        this.createdAt = now;
    }
}
