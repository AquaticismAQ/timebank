package cn.timebank.iam.infrastructure;

import cn.timebank.iam.domain.ActivationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ActivationCodeRepository extends JpaRepository<ActivationCode, String> {

    /** The only lookup there is: the code is its own key (10-StudentIdentityAndOnboarding.md §2.5). */
    Optional<ActivationCode> findByCodeHash(String codeHash);

    /**
     * Single-use enforcement, as one atomic compare-and-set rather than a
     * read-then-write. Two redemptions of the same code arriving together
     * would both see {@code redeemed_at IS NULL} under MySQL's REPEATABLE
     * READ and both proceed; making the {@code NULL} check part of the
     * {@code UPDATE}'s own {@code WHERE} clause means the database decides,
     * and exactly one caller gets a row count of 1.
     *
     * <p>Callers must treat {@code 0} as "somebody else already redeemed it"
     * and abandon the transaction — that return value is the guarantee, not a
     * diagnostic.</p>
     *
     * @return 1 if this call is the one that redeemed the code, 0 otherwise
     */
    @Modifying
    @Query("update ActivationCode c set c.redeemedAt = :now where c.id = :id and c.redeemedAt is null")
    int markRedeemed(@Param("id") String id, @Param("now") Instant now);

    /**
     * §2.7: reissuing a slip must retire the one it replaces. If a slip is lost
     * and a fresh code printed, leaving the lost code live for the rest of its
     * window would defeat the reason for reissuing at all. Only unredeemed rows
     * are touched — an already-redeemed code is history and stays.
     *
     * <p>Deleting rather than flagging: the row's only purpose is to be
     * redeemed, and issuance is recorded in the audit log, so the provenance a
     * retired row would have carried already survives elsewhere.</p>
     */
    @Modifying
    @Query("delete from ActivationCode c where c.userId = :userId and c.redeemedAt is null")
    int deleteUnredeemedFor(@Param("userId") String userId);
}
