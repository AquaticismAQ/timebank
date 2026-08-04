package cn.timebank.lifecycle.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * B6.7/D12: `account.balance` is a stored cache of `SUM(account_transaction.
 * amount)` for that account (invariant 1) — reconciliation recomputes the
 * sum independently and compares. When they disagree, a MySQL 8 window
 * function walks the ledger in order to name the exact transaction where
 * the running sum first stopped matching each row's own recorded {@code
 * balance_after} (itself written at credit/adjust time) — the "divergence
 * point" the exit criterion asks for, not just "somewhere in this account."
 */
@Repository
public class LedgerReconciliationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public LedgerReconciliationRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> accountIdsTouchedSince(Instant checkpoint) {
        return jdbcTemplate.queryForList(
                "select distinct account_id from account_transaction where created_at >= :checkpoint",
                new MapSqlParameterSource("checkpoint", checkpoint), String.class);
    }

    public List<String> allAccountIds() {
        return jdbcTemplate.queryForList("select id from account", new MapSqlParameterSource(), String.class);
    }

    public record MismatchCheck(String accountId, int storedBalance, int computedBalance, String divergentTransactionId) {
    }

    /** @return empty if the account's stored balance matches its ledger sum exactly. */
    public Optional<MismatchCheck> check(String accountId) {
        Integer storedBalance = jdbcTemplate.queryForObject("select balance from account where id = :id",
                new MapSqlParameterSource("id", accountId), Integer.class);
        if (storedBalance == null) {
            return Optional.empty(); // account deleted concurrently (e.g. a purge) — not this pass's concern
        }

        String sql = """
                select id, balance_after, sum(amount) over (order by created_at, id) as running_sum
                from account_transaction where account_id = :id order by created_at, id
                """;
        List<Object[]> rows = jdbcTemplate.query(sql, new MapSqlParameterSource("id", accountId),
                (rs, rowNum) -> new Object[]{rs.getString("id"), rs.getInt("balance_after"), rs.getInt("running_sum")});

        int computedBalance = rows.isEmpty() ? 0 : (int) rows.get(rows.size() - 1)[2];
        if (computedBalance == storedBalance) {
            return Optional.empty();
        }

        String divergentId = rows.stream()
                .filter(row -> (int) row[1] != (int) row[2])
                .map(row -> (String) row[0])
                .findFirst().orElse(null);
        return Optional.of(new MismatchCheck(accountId, storedBalance, computedBalance, divergentId));
    }
}
