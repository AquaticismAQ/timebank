package cn.timebank.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One permission code from the catalog (contract §2.4, §4.15's `GET /permissions`). */
@Entity
@Table(name = "permission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Permission {

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "code", length = 64, nullable = false, updatable = false)
    private String code;

    @Column(name = "description")
    private String description;

    public Permission(String id, String code, String description) {
        this.id = id;
        this.code = code;
        this.description = description;
    }
}
