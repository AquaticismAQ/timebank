package cn.timebank.notification.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.common.pagination.FilterSpecifications;
import cn.timebank.notification.application.dto.NotificationDto;
import cn.timebank.notification.domain.Notification;
import cn.timebank.notification.infrastructure.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
    public void notify(String recipientUserId, String templateCode, String relatedResourceType, String relatedResourceId) {
        Notification notification = new Notification(OpaqueIds.generate("ntf"), recipientUserId, templateCode,
                relatedResourceType, relatedResourceId, Instant.now());
        repository.save(notification);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationDto> listForUser(String userId, Pageable pageable, String read, String templateCode, String resolvedLanguage) {
        Specification<Notification> spec = FilterSpecifications.and(
                FilterSpecifications.equalTo("recipientUserId", userId),
                FilterSpecifications.equalToBoolean("read", read),
                FilterSpecifications.equalTo("templateCode", templateCode));
        Pageable effectivePageable = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"))
                : pageable;
        return repository.findAll(spec, effectivePageable).map(n -> toDto(n, resolvedLanguage));
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(String userId) {
        return repository.countByRecipientUserIdAndReadFalse(userId);
    }

    @Override
    @Transactional
    public NotificationDto markRead(String id, String userId, String resolvedLanguage) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Notification not found."));
        if (!notification.getRecipientUserId().equals(userId)) {
            throw ApiException.notFound("Notification not found.");
        }
        notification.setRead(true);
        repository.saveAndFlush(notification);
        return toDto(notification, resolvedLanguage);
    }

    @Override
    @Transactional
    public void markAllRead(String userId) {
        repository.markAllReadForRecipient(userId);
    }

    /**
     * I3.6: rendered fresh on every read, in the *reader's* resolved
     * language — never stored. A template that's been renamed/removed
     * since a historical notification was created falls back to the
     * template code itself rather than 500ing the whole list over one row.
     */
    private NotificationDto toDto(Notification n, String resolvedLanguage) {
        RenderedNotification rendered;
        try {
            rendered = templateService.render(n.getTemplateCode(), resolvedLanguage);
        } catch (RuntimeException e) {
            rendered = new RenderedNotification(n.getTemplateCode(), "");
        }
        return new NotificationDto(n.getId(), n.getTemplateCode(), rendered.title(), rendered.body(),
                n.getRelatedResourceType(), n.getRelatedResourceId(), n.isRead(), n.getCreatedAt());
    }
}
