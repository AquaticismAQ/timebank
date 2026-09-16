package cn.timebank.iam.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Reads for the activation expiry sweep
 * (10-StudentIdentityAndOnboarding.md §3.6). A purpose-built
 * {@code NamedParameterJdbcTemplate} read rather than a JPA entity for
 * {@code activation_code}, for the same reason as every other read-model
 * repository here (Design.md D6): the sweep needs two columns and a
 * timestamp, never the row as an object.
 *
 * <p><b>Both queries deliberately narrow candidates only — neither decides
 * anything.</b> The decision (has this account already activated? is any
 * code still live?) is made in Java by
 * {@code ActivationExpiryTransaction}, where it is covered by tests that a
 * query embedded in a SQL string could not be. The split also closes a real
 * race: the candidate query and the status write are not one statement, so
 * a student who activates in between must be re-checked against freshly
 * read state rather than against the row this query saw.</p>
 */
@Repository
public class ActivationExpiryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ActivationExpiryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Accounts worth looking at: still pending, and holding at least one
     * unredeemed code whose window has closed. Index-backed by V27's
     * {@code idx_activation_code_expires (expires_at, redeemed_at)}, which
     * exists for this query — without it this is a full scan of every code
     * ever issued, and the table only ever grows.
     *
     * <p>Oldest expiry first, so a backlog larger than one batch drains in a
     * predictable order instead of the same rows being re-read forever.</p>
     */
    public List<String> findPendingUserIdsWithAnExpiredCode(Instant now, int limit) {
        String sql = """
                select c.user_id
                from activation_code c
                join user u on u.id = c.user_id
                where u.status = 'PENDING_ACTIVATION'
                  and c.redeemed_at is null
                  and c.expires_at < :now
                group by c.user_id
                order by min(c.expires_at)
                limit :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("limit", limit);
        return jdbcTemplate.queryForList(sql, params, String.class);
    }

    /**
     * Every code ever issued to these users, expired or not. The sweep needs
     * all of them, not just the expired ones: §2.7 reissues codes to
     * still-pending students, so an account can hold an expired code and a
     * live replacement at once, and only the replacement decides its fate.
     */
    public List<ActivationWindow> findWindowsForUsers(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        String sql = "select user_id, expires_at, redeemed_at from activation_code where user_id in (:userIds)";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("userIds", userIds), (rs, rowNum) -> {
            Timestamp redeemedAt = rs.getTimestamp("redeemed_at");
            return new ActivationWindow(rs.getString("user_id"),
                    rs.getTimestamp("expires_at").toInstant(),
                    redeemedAt == null ? null : redeemedAt.toInstant());
        });
    }

    /** One issued activation code, reduced to the two facts that decide whether it can still be used. */
    public record ActivationWindow(String userId, Instant expiresAt, Instant redeemedAt) {

        /**
         * Whether this code could still be redeemed right now: single-use and
         * unspent (§2.5), and inside its window (§2.6).
         */
        public boolean isLive(Instant now) {
            return redeemedAt == null && expiresAt.isAfter(now);
        }
    }
}
