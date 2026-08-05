package cn.timebank.notification.infrastructure;

import cn.timebank.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Plain {@code JpaRepository}. B5.4/B5.5 add the read path: {@code
 * countByRecipientUserIdAndReadFalse} is the covering-index query behind
 * the polled unread-count endpoint (Design.md §16.1), and {@code
 * markAllReadForRecipient} is a single bulk {@code UPDATE} rather than
 * loading every unread row into the persistence context just to flip one
 * flag on each.
 */
public interface NotificationRepository extends JpaRepository<Notification, String>, JpaSpecificationExecutor<Notification> {

    long countByRecipientUserId(String recipientUserId);

    long countByRelatedResourceTypeAndRelatedResourceId(String relatedResourceType, String relatedResourceId);

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(String recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndReadFalse(String recipientUserId);

    @Modifying
    @Query("update Notification n set n.read = true where n.recipientUserId = :recipientUserId and n.read = false")
    int markAllReadForRecipient(@Param("recipientUserId") String recipientUserId);
}
