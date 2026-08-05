package cn.timebank.notification.application;

import cn.timebank.notification.application.dto.NotificationDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * B3.12's fifth approval effect: renders the named template for the
 * recipient's language and stores the result. Distinct from {@link
 * NotificationTemplateService}, which only renders — this is the module's
 * first real writer of a {@code notification} row. B5.4/B5.5 add the read
 * path.
 */
public interface NotificationService {

    void notify(String recipientUserId, String templateCode, String relatedResourceType, String relatedResourceId,
                String resolvedLanguage);

    Page<NotificationDto> listForUser(String userId, Pageable pageable, String read, String templateCode);

    /** The polled endpoint's single covering-index count query (B5.5). */
    long unreadCount(String userId);

    /** Owner-gated (contract §4.1): 404s if {@code id} isn't the caller's own notification. */
    NotificationDto markRead(String id, String userId);

    void markAllRead(String userId);
}
