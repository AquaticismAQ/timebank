package cn.timebank.notification.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.notification.domain.Notification;
import cn.timebank.notification.infrastructure.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository repository;
    private final NotificationTemplateService templateService;

    NotificationServiceImpl(NotificationRepository repository, NotificationTemplateService templateService) {
        this.repository = repository;
        this.templateService = templateService;
    }

    @Override
    @Transactional
    public void notify(String recipientUserId, String templateCode, String relatedResourceType,
                        String relatedResourceId, String resolvedLanguage) {
        RenderedNotification rendered = templateService.render(templateCode, resolvedLanguage);
        Notification notification = new Notification(OpaqueIds.generate("ntf"), recipientUserId, templateCode,
                rendered.title(), rendered.body(), relatedResourceType, relatedResourceId, Instant.now());
        repository.save(notification);
    }
}
