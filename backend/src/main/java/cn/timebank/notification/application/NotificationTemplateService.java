package cn.timebank.notification.application;

/**
 * B2.6 / B3's approval transaction. No parameter substitution yet — every
 * template seeded so far is a fixed string; add it when a template
 * actually needs one, not speculatively.
 */
public interface NotificationTemplateService {

    RenderedNotification render(String templateCode, String resolvedLanguage);
}
