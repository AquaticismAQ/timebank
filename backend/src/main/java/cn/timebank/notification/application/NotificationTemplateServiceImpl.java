package cn.timebank.notification.application;

import cn.timebank.common.i18n.LocalizedNames;
import cn.timebank.notification.domain.NotificationTemplate;
import cn.timebank.notification.infrastructure.NotificationTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationTemplateServiceImpl implements NotificationTemplateService {

    private final NotificationTemplateRepository repository;

    NotificationTemplateServiceImpl(NotificationTemplateRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public RenderedNotification render(String templateCode, String resolvedLanguage) {
        NotificationTemplate template = repository.findByCode(templateCode)
                .orElseThrow(() -> new IllegalStateException("Unknown notification template: " + templateCode));
        String title = LocalizedNames.pick(resolvedLanguage, template.getTitleZh(), template.getTitleEn());
        String body = LocalizedNames.pick(resolvedLanguage, template.getBodyZh(), template.getBodyEn());
        return new RenderedNotification(title, body);
    }
}
