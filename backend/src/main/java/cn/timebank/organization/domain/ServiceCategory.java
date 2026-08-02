package cn.timebank.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "service_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceCategory {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "code", length = 32, nullable = false, updatable = false)
    private String code;

    @Setter
    @Column(name = "name_zh", nullable = false)
    private String nameZh;

    @Setter
    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Setter
    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ServiceCategory(String id, String code, String nameZh, String nameEn, Instant now) {
        this.id = id;
        this.code = code;
        this.nameZh = nameZh;
        this.nameEn = nameEn;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
