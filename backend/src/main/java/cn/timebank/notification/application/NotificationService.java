package cn.timebank.notification.application;

import cn.timebank.notification.application.dto.NotificationDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * B3.12's fifth approval effect: records that a recipient should be
 * notified via the named template. Distinct from {@link
 * NotificationTemplateService}, which does the actual rendering — this is
 * the module's first real writer of a {@code notification} row. B5.4/B5.5
 * add the read path. I3.6: no language is resolved here anymore — a
 * notification's template code alone is stored, and {@link #listForUser}/
 * {@link #markRead} render title/body fresh in the *reader's* language on
 * every call, not the actor's language at creation time (see
 * {@code Notification}'s own doc comment for why this changed).
 */
public interface NotificationService {

    void notify(String recipientUserId, String templateCode, String relatedResourceType, String relatedResourceId);

    Page<NotificationDto> listForUser(String userId, Pageable pageable, String read, String templateCode, String resolvedLanguage);

    /** The polled endpoint's single covering-index count query (B5.5). */
    long unreadCount(String userId);

    /** Owner-gated (contract §4.1): 404s if {@code id} isn't the caller's own notification. */
    NotificationDto markRead(String id, String userId, String resolvedLanguage);

    void markAllRead(String userId);
}
