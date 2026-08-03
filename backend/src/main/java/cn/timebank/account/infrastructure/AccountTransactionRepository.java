package cn.timebank.account.infrastructure;

import cn.timebank.account.domain.AccountTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Deliberately extends the bare {@link Repository} marker, not {@code
 * JpaRepository}/{@code CrudRepository} — no {@code deleteById}, no {@code
 * delete}, no derivable update method (invariant 2), mirroring {@code
 * AuditLogRepository}'s structural enforcement of append-only.
 */
public interface AccountTransactionRepository extends Repository<AccountTransaction, String> {

    AccountTransaction save(AccountTransaction transaction);

    Optional<AccountTransaction> findById(String id);

    Page<AccountTransaction> findByAccountIdOrderByCreatedAtDesc(String accountId, Pageable pageable);

    long countByAccountId(String accountId);
}
