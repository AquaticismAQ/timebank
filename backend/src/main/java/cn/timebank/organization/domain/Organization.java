package cn.timebank.organization.domain;

import cn.timebank.common.concurrency.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "organization")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization extends VersionedEntity {

    public enum Type { SCHOOL, COMMUNITY, NONPROFIT, GOVERNMENT, OTHER }

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Setter
    @Column(name = "name", nullable = false)
    private String name;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 16, nullable = false)
    private Type type;

    @Setter
    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Setter
    @Column(name = "contact_name")
    private String contactName;

    @Setter
    @Column(name = "contact_email")
    private String contactEmail;

    @Setter
    @Column(name = "contact_phone")
    private String contactPhone;

    @Setter
    @Column(name = "address")
    private String address;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private OrganizationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Organization(String id, String name, Type type, Instant now) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.status = OrganizationStatus.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
