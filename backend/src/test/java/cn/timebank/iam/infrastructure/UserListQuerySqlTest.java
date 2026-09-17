package cn.timebank.iam.infrastructure;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.iam.application.dto.UserListFilter;
import cn.timebank.iam.web.UserController;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue 094, the layer {@code UserListFilterSortTest} cannot reach: that test
 * proves the controller and service forward every parameter, but it stops at
 * the repository's door because there is no database in {@code mvn test}. What
 * is left to go wrong is the repository accepting a filter and building SQL
 * that does not mention it — so these assertions are about the statement and
 * its bound parameters, never about a query running.
 */
class UserListQuerySqlTest {

    private final UserListQueryRepository repository = new UserListQueryRepository(null);

    // ── filters reach the statement ──────────────────────────────────────

    @Test
    void every_supplied_filter_adds_a_predicate_and_binds_its_value() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String clause = repository.filterClause(
                new UserListFilter("Bravo", "student", "INACTIVE", "grd_6", "cls_6a"), params);

        assertThat(clause)
                .contains("lower(u.display_name) like :search")
                .contains("r2.code = :roleCode")
                // Issue 097: the *derived* status, not the raw column — against
                // `u.status` the LOCKED option matched rows nothing writes.
                .contains(UserListQueryRepository.EFFECTIVE_STATUS + " = :status")
                .doesNotContain(" and u.status = :status")
                .contains("s.grade_id = :gradeId")
                .contains("s.class_id = :classId");
        assertThat(params.getValue("search")).isEqualTo("%bravo%");
        assertThat(params.getValue("roleCode")).isEqualTo("student");
        assertThat(params.getValue("status")).isEqualTo("INACTIVE");
        assertThat(params.getValue("gradeId")).isEqualTo("grd_6");
        assertThat(params.getValue("classId")).isEqualTo("cls_6a");
    }

    @Test
    void an_absent_filter_adds_nothing() {
        // The control for the test above: without it, a filterClause that
        // unconditionally emitted all five predicates would still pass.
        MapSqlParameterSource params = new MapSqlParameterSource();
        assertThat(repository.filterClause(UserListFilter.NONE, params)).isEmpty();
        assertThat(params.getValues()).isEmpty();
    }

    @Test
    void a_blank_filter_is_treated_as_absent_rather_than_matching_the_empty_string() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        assertThat(repository.filterClause(new UserListFilter("  ", "", " ", "", null), params)).isEmpty();
    }

    @Test
    void each_filter_is_independent_of_the_others() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String clause = repository.filterClause(new UserListFilter(null, null, null, "grd_8", null), params);
        assertThat(clause).contains("s.grade_id = :gradeId");
        assertThat(clause).doesNotContain(":roleCode").doesNotContain(":status").doesNotContain(":classId");
    }

    @Test
    void the_role_filter_uses_exists_so_a_multi_role_row_is_not_duplicated() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String clause = repository.filterClause(new UserListFilter(null, "teacher", null, null, null), params);
        assertThat(clause).contains("exists (select 1 from user_role");
        assertThat(clause).doesNotContain("join user_role ur on");
    }

    // ── sorting reaches the statement ────────────────────────────────────

    @Test
    void ascending_and_descending_produce_different_order_by_clauses() {
        for (String property : List.of("displayName", "gradeLevel", "className")) {
            String ascending = repository.orderByClause(Sort.by(Sort.Order.asc(property)));
            String descending = repository.orderByClause(Sort.by(Sort.Order.desc(property)));
            assertThat(ascending).endsWith(" asc");
            assertThat(descending).endsWith(" desc");
            assertThat(descending).isNotEqualTo(ascending);
        }
    }

    @Test
    void grade_sorts_by_sequence_and_class_by_name() {
        // grade_id would sort grade_10/grade_11 ahead of grade_6 — the trap the
        // students list documents. Assert the real column, not merely that
        // something was emitted.
        assertThat(repository.orderByClause(Sort.by(Sort.Order.asc("gradeLevel")))).isEqualTo(" order by g.sequence asc");
        assertThat(repository.orderByClause(Sort.by(Sort.Order.desc("className")))).isEqualTo(" order by c.name desc");
        assertThat(repository.orderByClause(Sort.by(Sort.Order.asc("displayName"))))
                .isEqualTo(" order by u.display_name asc");
    }

    @Test
    void the_id_tiebreaker_is_preserved_after_the_requested_column() {
        Pageable pageable = PageableFactory.toPageable(
                new PageQuery(1, 20, List.of(new PageQuery.SortToken("gradeLevel", true)), Map.of(), null),
                UserController.SORTABLE);
        assertThat(repository.orderByClause(pageable.getSort())).isEqualTo(" order by g.sequence desc, u.id asc");
    }

    @Test
    void an_unsorted_page_still_gets_a_deterministic_order() {
        assertThat(repository.orderByClause(Sort.unsorted())).isEqualTo(" order by u.id asc");
    }

    // ── the whitelist and the column map cannot drift apart ──────────────

    @Test
    void every_sortable_property_the_controller_accepts_has_a_column() {
        // The failure mode this catches is a 500 in production: PageableFactory
        // would happily accept the token and orderByClause would then throw.
        assertThat(UserListQueryRepository.SORT_COLUMNS.keySet())
                .containsExactlyInAnyOrderElementsOf(UserController.SORTABLE);
    }

    @Test
    void an_unmapped_sort_property_fails_loudly_rather_than_being_dropped() {
        assertThatThrownBy(() -> repository.orderByClause(Sort.by(Sort.Order.asc("scopeGradeIds"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scopeGradeIds");
    }

    // ── the derived status (issue 097) ───────────────────────────────────

    /**
     * {@code LOCKED} is computed, not stored: a brute-force lockout writes
     * {@code locked_until} and never {@code status}. The rule therefore exists
     * twice — {@code User.effectiveStatus()} in Java, and this constant, because
     * the list has to order and count by the result and so cannot call Java.
     *
     * <p>{@code EffectiveStatusTest} owns the Java copy. These read the SQL as
     * text: what goes wrong with two copies of one rule is that one is edited
     * and the other is not, which exercising the surviving copy never reveals.
     * {@code UserListFilterSortIT} then runs the statement against real MySQL,
     * which is the only thing that can prove the expression parses.</p>
     */
    @Test
    void the_derived_status_expresses_the_same_rule_as_the_domain() {
        assertThat(UserListQueryRepository.EFFECTIVE_STATUS)
                .as("must test the same column the Java side tests")
                .contains("u.locked_until")
                .as("a null locked_until is not a lock")
                .contains("u.locked_until is not null")
                .as("the window is the future, not any value at all")
                .contains("> now(3)")
                .as("and must yield the same literal the enum name produces")
                .contains("'LOCKED'")
                .as("falling through to the stored column, exactly as effectiveStatus() does")
                .contains("else u.status end");
    }

    /**
     * {@code now(3)}, not {@code now()}: {@code locked_until} is
     * {@code DATETIME(3)} and the Java side compares against an {@code Instant}.
     * Second precision would disagree with {@code isLocked()} for up to a second
     * at each end of the window — rarely, unreproducibly, and only in production.
     */
    @Test
    void the_derived_status_compares_at_the_columns_own_precision() {
        assertThat(UserListQueryRepository.EFFECTIVE_STATUS).doesNotContain("> now()");
    }

    /**
     * The projection and the filter must use the one constant. Two hand-written
     * copies would let the badge a row displays disagree with the set the filter
     * returns — the exact defect issue 097 exists to fix, reintroduced one layer
     * down.
     */
    @Test
    void the_projection_and_the_filter_derive_the_status_the_same_way() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String clause = repository.filterClause(
                new UserListFilter(null, null, "LOCKED", null, null), params);

        assertThat(clause).contains(UserListQueryRepository.EFFECTIVE_STATUS);
        assertThat(repository.queryPageSql(new UserListFilter(null, null, null, null, null),
                        PageableFactory.toPageable(
                                new PageQuery(1, 20, List.of(), Map.of(), null), UserController.SORTABLE),
                        params))
                .contains(UserListQueryRepository.EFFECTIVE_STATUS + " as status")
                .as("the raw column must still be selected — the UI shows when a lock releases")
                .contains("u.locked_until");
    }
}
