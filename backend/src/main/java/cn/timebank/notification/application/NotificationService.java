package cn.timebank.notification.application;

/**
 * B3.12's fifth approval effect: renders the named template for the
 * recipient's language and stores the result. Distinct from {@link
 * NotificationTemplateService}, which only renders — this is the module's
 * first real writer of a {@code notification} row.
 */
public interface NotificationService {

    void notify(String recipientUserId, String templateCode, String relatedResourceType, String relatedResourceId,
                String resolvedLanguage);
}
