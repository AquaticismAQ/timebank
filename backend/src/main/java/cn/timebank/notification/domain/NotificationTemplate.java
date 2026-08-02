package cn.timebank.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notification_template")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationTemplate {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "code", length = 64, nullable = false, updatable = false)
    private String code;

    @Column(name = "title_zh", nullable = false)
    private String titleZh;

    @Column(name = "title_en", nullable = false)
    private String titleEn;

    @Column(name = "body_zh", nullable = false)
    private String bodyZh;

    @Column(name = "body_en", nullable = false)
    private String bodyEn;
}
