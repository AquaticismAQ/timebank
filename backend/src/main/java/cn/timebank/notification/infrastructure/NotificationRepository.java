package cn.timebank.notification.infrastructure;

import cn.timebank.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plain {@code JpaRepository} — unlike the append-only ledger/review-decision
 * tables, a notification's {@code is_read} flag is expected to be updated
 * once B5 builds the mark-read endpoint. No such endpoint exists yet; this
 * repository exists only so B3.12 has somewhere to {@code save} to.
 */
public interface NotificationRepository extends JpaRepository<Notification, String> {

    long countByRecipientUserId(String recipientUserId);

    long countByRelatedResourceTypeAndRelatedResourceId(String relatedResourceType, String relatedResourceId);
}
