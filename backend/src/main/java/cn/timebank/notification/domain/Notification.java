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
 * template rendering ("the notification row itself... is B5's"). Rendered
 * at creation time (title/body are a snapshot), since re-rendering per
 * request would need the recipient's language resolved at *read* time,
 * which no read endpoint exists to do yet (B5's).
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

    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "related_resource_type", updatable = false)
    private String relatedResourceType;

    @Column(name = "related_resource_id", updatable = false)
    private String relatedResourceId;

    @Setter
    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Notification(String id, String recipientUserId, String templateCode, String title, String body,
                         String relatedResourceType, String relatedResourceId, Instant now) {
        this.id = id;
        this.recipientUserId = recipientUserId;
        this.templateCode = templateCode;
        this.title = title;
        this.body = body;
        this.relatedResourceType = relatedResourceType;
        this.relatedResourceId = relatedResourceId;
        this.read = false;
        this.createdAt = now;
    }
}
