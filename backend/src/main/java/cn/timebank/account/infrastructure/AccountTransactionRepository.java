package cn.timebank.account.infrastructure;

import cn.timebank.account.domain.AccountTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    /**
     * Total credited, per account, for a page of accounts (issue 152).
     *
     * <p>Positive amounts only: "earned" means what came in, so a correction
     * that takes coins back out is not a negative earning, it is a separate
     * fact the balance already carries.</p>
     *
     * <p>Grouped over the whole page rather than one aggregate per row —
     * a list of two hundred accounts must not become two hundred and one
     * queries, the same reason the scope totals are one criteria query
     * rather than a fold over `findAll`.</p>
     */
    @Query("""
            select t.accountId, sum(t.amount)
              from AccountTransaction t
             where t.accountId in :accountIds and t.amount > 0
             group by t.accountId
            """)
    List<Object[]> sumCreditedByAccountIds(@Param("accountIds") Collection<String> accountIds);
}
