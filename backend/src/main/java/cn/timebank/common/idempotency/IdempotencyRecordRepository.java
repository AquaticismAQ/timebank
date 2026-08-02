package cn.timebank.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    Optional<IdempotencyRecord> findByIdempotencyKeyAndPrincipalIdAndEndpoint(
            String idempotencyKey, String principalId, String endpoint);
}
