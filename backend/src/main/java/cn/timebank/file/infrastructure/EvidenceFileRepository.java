package cn.timebank.file.infrastructure;

import cn.timebank.file.domain.EvidenceFile;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A file's own row is mutable across its {@code PENDING → PROCESSING →
 * READY|REJECTED} lifecycle, unlike the append-only ledger/review-decision
 * tables — a plain {@code JpaRepository} is correct here.
 */
public interface EvidenceFileRepository extends JpaRepository<EvidenceFile, String> {
}
