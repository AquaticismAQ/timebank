package cn.timebank.common.concurrency;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Every editable entity carries {@code version} (contract §2.8, Design.md
 * §11). JPA's {@code @Version} enforces the check at flush time; {@link
 * ConcurrencyControl#checkVersion} enforces it earlier, against the
 * client-supplied version, so a stale write fails fast with a clear
 * message rather than surfacing as a generic Hibernate exception.
 */
@MappedSuperclass
public abstract class VersionedEntity {

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 1L;

    public Long getVersion() {
        return version;
    }
}
