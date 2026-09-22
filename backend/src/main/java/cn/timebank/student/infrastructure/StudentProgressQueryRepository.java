package cn.timebank.student.infrastructure;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Approved service minutes per student for one academic year (issue 159).
 *
 * <h2>Why raw SQL, and why here</h2>
 *
 * <p>{@code GET /students} has to know each row's progress for two reasons: the
 * list shows it, and it is filterable. Both used to be done in the browser — the
 * page fetched {@code /students/{id}/progress} once per row and then filtered the
 * twenty rows it had, while the pager kept reporting the unfiltered total, so a
 * selective filter emptied whole pages that the pager still counted.</p>
 *
 * <p>The sums live in {@code service_record}, which belongs to another module,
 * and the obvious routes are both closed. A JPA criteria subquery would need the
 * {@code ServiceRecord} entity — a compile-time reach into
 * {@code servicerecord.domain} that {@code ModuleBoundaryTest} forbids. Injecting
 * {@code ServiceRecordService} would close a bean cycle, because
 * {@code ServiceRecordServiceImpl} already injects {@code StudentService}.</p>
 *
 * <p>So this reads the table in SQL and carries <b>no Java dependency</b> on that
 * module — the same argument {@code AuditLogQueryServiceImpl} makes for joining
 * {@code user}, and {@code UserListQueryRepository} for reaching {@code student}.
 * The cost is that a column rename in {@code service_record} has to be followed
 * here, which is why the three column names are the only thing this file
 * knows about it.</p>
 *
 * <h2>APPROVED only</h2>
 *
 * <p>Matching {@code ProgressServiceImpl}: a submitted record is a claim, not an
 * achievement, and counting it would tell a student they are on track for work
 * nobody has checked. Pending minutes appear in the progress endpoint as their
 * own field, never folded into the completed total.</p>
 */
@Repository
public class StudentProgressQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    StudentProgressQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param studentIds the page's students, or every candidate when a progress
     *                   filter is being resolved. Empty in, empty out — {@code in ()}
     *                   is not valid SQL and "no students" has an obvious answer.
     * @return student id → approved minutes inside the window. A student with no
     *         approved record is <b>absent</b>, not zero; the caller defaults,
     *         which keeps "no rows" and "zero minutes" distinguishable here.
     */
    public Map<String, Long> approvedMinutesByStudent(Collection<String> studentIds, LocalDate from, LocalDate to) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("studentIds", studentIds)
                .addValue("from", from)
                .addValue("to", to);
        String sql = """
                select sr.student_id as student_id, coalesce(sum(sr.duration_minutes), 0) as minutes
                  from service_record sr
                 where sr.student_id in (:studentIds)
                   and sr.status = 'APPROVED'
                   and sr.service_date between :from and :to
                 group by sr.student_id
                """;
        Map<String, Long> minutes = new HashMap<>();
        jdbcTemplate.query(sql, params, rs -> {
            minutes.put(rs.getString("student_id"), rs.getLong("minutes"));
        });
        return minutes;
    }

    /**
     * Time-coin balance per student, for sorting the roster by it (issue 159).
     *
     * <p>Same boundary argument as the minutes above: {@code account} belongs to
     * another module, the balance is not a column on {@code student}, and sorting
     * a page by a value merged in afterwards is what made "highest balances" mean
     * "highest of these twenty".</p>
     *
     * <p>Reads the stored {@code balance} cache rather than re-summing the ledger
     * — that column is the account module's own answer to "what is this balance",
     * and a second derivation here could disagree with the page it sorts.</p>
     */
    public Map<String, Long> balanceByStudent(Collection<String> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("studentIds", studentIds);
        String sql = "select a.student_id as student_id, a.balance as balance"
                + " from account a where a.student_id in (:studentIds)";
        Map<String, Long> balances = new HashMap<>();
        jdbcTemplate.query(sql, params, rs -> {
            balances.put(rs.getString("student_id"), rs.getLong("balance"));
        });
        return balances;
    }
}
