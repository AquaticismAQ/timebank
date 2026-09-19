package cn.timebank.iam.infrastructure;

import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.application.dto.UserListFilter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Issue 094: the `GET /users` list read model.
 *
 * <p><b>Why this is not JPA.</b> The Users tab has to sort and filter by the
 * account holder's grade and class, which live on {@code student} —
 * another module's table. A JPA association from {@code iam.domain.User} to
 * {@code student.domain.Student} would be exactly the cross-module reach-in
 * {@code ModuleBoundaryTest} forbids, and resolving grade through {@code
 * StudentService} in Java can populate the columns but cannot order a page by
 * them. So this follows the route {@code ReviewQueueRepository} already
 * established for the same problem: a purpose-built read-model query (Design.md
 * §6's "or a read-model query" clause) whose raw SQL carries no Java dependency
 * on another module's classes.</p>
 *
 * <p>Writes and single-row reads stay on {@code UserRepository}/JPA — this
 * class is list-shaped only, and returns {@code UserDto} directly rather than
 * an entity.</p>
 */
@Repository
public class UserListQueryRepository {

    /**
     * Maps a {@link Sort} property (a wire-level {@code UserDto} field name)
     * to its SQL ordering expression. Keys must exactly match {@code
     * UserController.SORTABLE} — that whitelist is the only thing standing
     * between a raw {@code sort} parameter and this map, and nothing here is
     * ever string-concatenated from user input.
     *
     * <p>{@code gradeLevel} orders by the grade's {@code sequence}, never by
     * {@code grade_id}: the ids sort lexically, which puts {@code grade_10}
     * and {@code grade_11} ahead of {@code grade_6} — the same trap {@code
     * StudentList.tsx} documents for why the students list never offered a
     * server-side grade sort.</p>
     */
    static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "u.id",
            "username", "u.username",
            "displayName", "u.display_name",
            "createdAt", "u.created_at",
            "gradeLevel", "g.sequence",
            "className", "c.name");

    /**
     * The SQL mirror of {@code User.effectiveStatus()} (issue 097) — a
     * brute-force lockout writes {@code locked_until} and never {@code status},
     * so the stored column reports a locked-out account as {@code ACTIVE}.
     *
     * <p>Used in <b>both</b> the projection and the filter, from this one
     * constant, so the badge a row displays and the set the {@code LOCKED}
     * filter returns cannot disagree. Writing the expression twice is exactly
     * how they would.</p>
     *
     * <p>{@code now(3)} matches the column's {@code DATETIME(3)} precision and
     * the {@code Instant.now()} the Java side compares against.</p>
     */
    static final String EFFECTIVE_STATUS =
            "(case when u.locked_until is not null and u.locked_until > now(3) then 'LOCKED' else u.status end)";

    /**
     * One row per account regardless of how many {@code user_role} rows it
     * has. 07-RoleFinetune.md §2.5 says exactly one, but a join would turn a
     * not-yet-migrated two-role account into two rows and quietly corrupt
     * {@code totalItems} and every page boundary — a correlated subquery
     * cannot. {@code order by r.code} makes the pick deterministic where
     * {@code UserServiceImpl.soleRoleCodeOf}'s {@code findFirst} over a
     * {@code Set} is not.
     */
    private static final String SELECT_COLUMNS = """
            select u.id, u.username, u.display_name, u.email, u.student_id, u.staff_id,
                   {effectiveStatus} as status,
                   -- The raw column too, so the UI can say *when* the lock
                   -- releases rather than only that it exists.
                   u.locked_until,
                   u.preferred_language, u.version, u.created_at, u.updated_at,
                   (select r.code from user_role ur join role r on r.id = ur.role_id
                     where ur.user_id = u.id order by r.code limit 1) as role_code,
                   s.grade_id as grade_id, g.sequence as grade_level,
                   s.class_id as class_id, c.name as class_name,
                   -- Issue 094 §7: there is no `user.last_login` column; the
                   -- newest successful login_log row is the only source. A
                   -- correlated subquery rather than a join, for the same reason
                   -- role_code is one: joining login_log would multiply every
                   -- user row by their login count and corrupt the page counts.
                   (select max(ll.created_at) from login_log ll
                     where ll.user_id = u.id and ll.success = 1) as last_login_at
            """.replace("{effectiveStatus}", EFFECTIVE_STATUS);

    /**
     * {@code left join}, not {@code join}: a staff account has no {@code
     * student_id} at all, and dropping every one of them from the user list
     * the moment grade became a column would be a spectacular regression.
     */
    private static final String FROM_AND_JOINS = """
            from `user` u
            left join student s on s.id = u.student_id
            left join grade g on g.id = s.grade_id
            left join class c on c.id = s.class_id
            where 1 = 1""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public UserListQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Package-private so {@code UserListQuerySqlTest} can assert on the
     * projection as well as the filter — the two have to derive {@code status}
     * identically (issue 097), and that is a property of the statement, not of
     * any row it returns.
     */
    String queryPageSql(UserListFilter filter, Pageable pageable, MapSqlParameterSource params) {
        return SELECT_COLUMNS + FROM_AND_JOINS + filterClause(filter, params)
                + orderByClause(pageable.getSort()) + " limit :limit offset :offset";
    }

    public List<UserDto> queryPage(UserListFilter filter, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        String sql = queryPageSql(filter, pageable, params);

        return jdbcTemplate.query(sql, params, (rs, rowNum) -> {
            Timestamp createdAt = rs.getTimestamp("created_at");
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            // getObject, not getInt: a staff account's grade_level is SQL NULL,
            // and getInt would silently read it as grade 0.
            Integer gradeLevel = (Integer) rs.getObject("grade_level");
            Timestamp lastLogin = rs.getTimestamp("last_login_at");
            Timestamp lockedUntil = rs.getTimestamp("locked_until");
            return new UserDto(
                    rs.getString("id"), rs.getString("username"), rs.getString("display_name"),
                    rs.getString("email"), rs.getString("student_id"), rs.getString("staff_id"),
                    rs.getString("status"), rs.getString("preferred_language"), rs.getString("role_code"),
                    rs.getLong("version"),
                    createdAt != null ? createdAt.toInstant() : null,
                    updatedAt != null ? updatedAt.toInstant() : null,
                    // Filled in by UserController, which is where iam and security
                    // are allowed to meet — see UserDto.withScopeGradeIds.
                    Set.of(),
                    rs.getString("grade_id"), gradeLevel,
                    rs.getString("class_id"), rs.getString("class_name"),
                    lastLogin != null ? lastLogin.toInstant() : null,
                    // Kept as the raw column even though `status` above is
                    // derived from it: an already-expired value is `null` to the
                    // reader by way of the derived status, and reporting the
                    // past instant it holds would be noise.
                    lockedUntil != null && lockedUntil.toInstant().isAfter(java.time.Instant.now())
                            ? lockedUntil.toInstant() : null);
        });
    }

    /** Same filters as {@link #queryPage} — {@code totalItems} must describe the filtered set, not every account. */
    public long countMatching(UserListFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = "select count(*) " + FROM_AND_JOINS + filterClause(filter, params);
        Long result = jdbcTemplate.queryForObject(sql, params, Long.class);
        return result == null ? 0 : result;
    }

    /**
     * Package-private so {@code UserListQuerySqlTest} can assert that each
     * supplied filter actually reaches the SQL. The failure this guards
     * against is a parameter that is accepted and then ignored, which from
     * the outside is indistinguishable from one that works.
     */
    String filterClause(UserListFilter filter, MapSqlParameterSource params) {
        StringBuilder clause = new StringBuilder();
        if (filter.hasSearch()) {
            params.addValue("search", "%" + filter.search().toLowerCase() + "%");
            // Username as well as display name (issue 106). An administrator
            // identifying an account is usually holding a credential slip, and
            // a slip prints the username and the name — never the email. Since
            // issue 105 that username is 8 random digits, so it is also the
            // half a name search cannot help with.
            //
            // Parenthesised: the filters below append their own `and`, and
            // without the brackets the trailing one would bind to the `or` and
            // quietly widen every other filter.
            clause.append(" and (lower(u.display_name) like :search or lower(u.username) like :search)");
        }
        if (filter.hasRoleCode()) {
            // `exists`, not a join: see SELECT_COLUMNS — the row count must not
            // depend on how many roles an account happens to hold.
            params.addValue("roleCode", filter.roleCode());
            clause.append(" and exists (select 1 from user_role ur2 join role r2 on r2.id = ur2.role_id"
                    + " where ur2.user_id = u.id and r2.code = :roleCode)");
        }
        if (filter.hasStatus()) {
            // Not validated against UserStatus here: an unrecognized value matches
            // no row, which is FilterSpecifications.equalToEnum's documented
            // behaviour too — a filter typo is an empty page, not a 500.
            //
            // Matches the *derived* status, not the column (issue 097). Against
            // the raw column, `LOCKED` asked for rows nothing ever writes, and
            // `ACTIVE` returned accounts that were locked out at that moment.
            params.addValue("status", filter.status());
            clause.append(" and ").append(EFFECTIVE_STATUS).append(" = :status");
        }
        if (filter.hasGradeId()) {
            params.addValue("gradeId", filter.gradeId());
            clause.append(" and s.grade_id = :gradeId");
        }
        if (filter.hasClassId()) {
            params.addValue("classId", filter.classId());
            clause.append(" and s.class_id = :classId");
        }
        return clause.toString();
    }

    /**
     * {@code sort} is never empty in practice — {@code PageableFactory}
     * always appends an {@code id} tiebreaker — but an unsorted page still
     * gets a deterministic order rather than MySQL's arbitrary one.
     *
     * <p>Accounts with no grade (every staff account) sort as SQL NULL:
     * first ascending, last descending. That is deliberate — it keeps the
     * students, who are the reason the column exists, contiguous at one
     * end rather than interleaved under a fabricated grade 0.</p>
     */
    String orderByClause(Sort sort) {
        String orderBy = sort.stream()
                .map(order -> {
                    String column = SORT_COLUMNS.get(order.getProperty());
                    if (column == null) {
                        throw new IllegalStateException("Unmapped user-list sort property: " + order.getProperty());
                    }
                    return column + (order.isDescending() ? " desc" : " asc");
                })
                .collect(Collectors.joining(", "));
        return orderBy.isEmpty() ? " order by u.id asc" : " order by " + orderBy;
    }
}
