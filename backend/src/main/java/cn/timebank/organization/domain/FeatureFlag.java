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

/** Phase 3/4 capability switches (Design.md §14) — flags only, nothing they guard is built. */
@Entity
@Table(name = "feature_flag")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeatureFlag {

    @Id
    @Column(name = "code", length = 32, nullable = false, updatable = false)
    private String code;

    @Setter
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
