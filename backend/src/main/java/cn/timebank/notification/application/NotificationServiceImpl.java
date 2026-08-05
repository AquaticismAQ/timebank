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
    public void notify(String recipientUserId, String templateCode, String relatedResourceType,
                        String relatedResourceId, String resolvedLanguage) {
        RenderedNotification rendered = templateService.render(templateCode, resolvedLanguage);
        Notification notification = new Notification(OpaqueIds.generate("ntf"), recipientUserId, templateCode,
                rendered.title(), rendered.body(), relatedResourceType, relatedResourceId, Instant.now());
        repository.save(notification);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationDto> listForUser(String userId, Pageable pageable, String read, String templateCode) {
        Specification<Notification> spec = FilterSpecifications.and(
                FilterSpecifications.equalTo("recipientUserId", userId),
                FilterSpecifications.equalToBoolean("read", read),
                FilterSpecifications.equalTo("templateCode", templateCode));
        Pageable effectivePageable = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"))
                : pageable;
        return repository.findAll(spec, effectivePageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(String userId) {
        return repository.countByRecipientUserIdAndReadFalse(userId);
    }

    @Override
    @Transactional
    public NotificationDto markRead(String id, String userId) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Notification not found."));
        if (!notification.getRecipientUserId().equals(userId)) {
            throw ApiException.notFound("Notification not found.");
        }
        notification.setRead(true);
        repository.saveAndFlush(notification);
        return toDto(notification);
    }

    @Override
    @Transactional
    public void markAllRead(String userId) {
        repository.markAllReadForRecipient(userId);
    }

    private NotificationDto toDto(Notification n) {
        return new NotificationDto(n.getId(), n.getTemplateCode(), n.getTitle(), n.getBody(),
                n.getRelatedResourceType(), n.getRelatedResourceId(), n.isRead(), n.getCreatedAt());
    }
}
