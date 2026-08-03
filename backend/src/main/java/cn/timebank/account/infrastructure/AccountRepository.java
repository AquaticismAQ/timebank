package cn.timebank.account.infrastructure;

import cn.timebank.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String>, JpaSpecificationExecutor<Account> {

    Optional<Account> findByStudentId(String studentId);

    /**
     * B3.9: {@code SELECT ... FOR UPDATE} on the account row, expressed
     * through Spring Data's pessimistic-lock support rather than raw JDBC —
     * every credit/adjust/reverse/freeze goes through this so concurrent
     * mutations to the same account serialize at the database rather than
     * interleaving (D12).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findWithLockById(String id);

    /**
     * The locked lookup by student id — used as the *only* read for an
     * already-existing account (see {@code AccountServiceImpl.getOrCreateLocked}).
     * An earlier version did an unlocked {@link #findByStudentId} first and
     * then this locked query second within the same transaction; Hibernate's
     * first-level cache returns the *same* managed instance for a repeat
     * query against an identity already loaded in that persistence context,
     * so the lock was acquired at the database but the in-memory
     * {@code version} field was never refreshed to what the lock then
     * revealed — surfacing as a real, reproducible
     * {@code ObjectOptimisticLockingFailureException} under concurrent
     * credits (caught by {@code AccountConcurrencyIT}). One locked query,
     * once, avoids the trap entirely.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.studentId = :studentId")
    Optional<Account> findWithLockByStudentId(String studentId);
}
