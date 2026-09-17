package cn.timebank.audit.application;

import cn.timebank.audit.web.AuditLogController;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue 098. Two parameters on {@code GET /audit-logs} were accepted and then
 * discarded, which from the outside is indistinguishable from working:
 *
 * <ul>
 *   <li>{@code search} was never read out of {@code PageQuery} —
 *       {@code ?search=nonsense-xyzzy} returned all 1,045 rows.</li>
 *   <li>The statement ended in a hardcoded {@code order by created_at desc},
 *       so {@code ?sort=createdAt} (ascending), which the controller validated
 *       and accepted, returned the same newest-first page as
 *       {@code -createdAt}. Ascending order was unreachable.</li>
 * </ul>
 *
 * <p>These assertions are about the generated statement and its bound
 * parameters; nothing is executed here. {@code AuditLogQueryIT} runs it against
 * real MySQL.</p>
 */
class AuditLogQuerySqlTest {

    private final AuditLogQueryServiceImpl service = new AuditLogQueryServiceImpl(null);

    // ── search (was silently dropped) ────────────────────────────────────

    @Test
    void search_reaches_the_statement_and_binds_a_lowercased_wildcard() {
        MapSqlParameterSource params = new MapSqlParameterSource();

        String where = service.whereClause("Wei CHEN", null, null, null, null, null, null, params);

        assertThat(where).contains(":search");
        assertThat(params.getValue("search")).isEqualTo("%wei chen%");
    }

    /**
     * The actor's display name is the term an administrator actually knows; the
     * other three are the ones they can copy from somewhere else. Asserted by
     * column so that narrowing the search later is a deliberate edit rather than
     * something nobody notices.
     */
    @Test
    void search_covers_the_actor_name_the_action_the_resource_and_the_request_id() {
        MapSqlParameterSource params = new MapSqlParameterSource();

        String where = service.whereClause("anything", null, null, null, null, null, null, params);

        assertThat(where)
                .contains("lower(u.display_name) like :search")
                .contains("lower(a.action) like :search")
                .contains("lower(a.resource_id) like :search")
                .contains("lower(a.request_id) like :search");
    }

    @Test
    void a_blank_search_adds_no_predicate_rather_than_matching_everything_slowly() {
        MapSqlParameterSource params = new MapSqlParameterSource();

        assertThat(service.whereClause("   ", null, null, null, null, null, null, params)).isEmpty();
        // `hasValue`, not `getValue`: the latter throws for an unregistered key,
        // which passes the test for the wrong reason if it is ever asserted on.
        assertThat(params.hasValue("search")).isFalse();
    }

    // ── filters ──────────────────────────────────────────────────────────

    @Test
    void every_supplied_filter_adds_a_predicate_and_binds_its_value() {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-30T00:00:00Z");

        String where = service.whereClause(null, "user.login", "user", "usr_1", "usr_2", from, to, params);

        assertThat(where)
                .contains("a.action = :action")
                .contains("a.resource_type = :resourceType")
                .contains("a.resource_id = :resourceId")
                .contains("a.actor_id = :actorId")
                .contains("a.created_at >= :createdFrom")
                .contains("a.created_at <= :createdTo");
        assertThat(params.getValue("action")).isEqualTo("user.login");
        assertThat(params.getValue("actorId")).isEqualTo("usr_2");
    }

    @Test
    void no_filters_produces_no_where_clause() {
        assertThat(service.whereClause(null, null, null, null, null, null, null, new MapSqlParameterSource()))
                .isEmpty();
    }

    // ── order by (was hardcoded) ─────────────────────────────────────────

    /** The defect itself: ascending must actually be ascending. */
    @Test
    void an_ascending_sort_is_honoured_rather_than_overridden() {
        assertThat(service.orderByClause(Sort.by("createdAt").ascending()))
                .contains("a.created_at asc")
                .doesNotContain("a.created_at desc");
    }

    @Test
    void a_descending_sort_and_an_ascending_one_differ() {
        assertThat(service.orderByClause(Sort.by("createdAt").ascending()))
                .isNotEqualTo(service.orderByClause(Sort.by("createdAt").descending()));
    }

    @Test
    void an_unsorted_page_still_reads_newest_first() {
        assertThat(service.orderByClause(Sort.unsorted())).contains("a.created_at desc");
    }

    /**
     * {@code created_at} is not unique — one transaction writes several rows in
     * the same millisecond. Without a tiebreaker MySQL may order them
     * differently for different pages, which shows some rows twice across a page
     * boundary and hides others entirely.
     */
    @Test
    void every_ordering_ends_with_a_unique_tiebreaker() {
        assertThat(service.orderByClause(Sort.unsorted())).endsWith("a.id desc");
        assertThat(service.orderByClause(Sort.by("action").ascending())).endsWith("a.id desc");
        assertThat(service.orderByClause(Sort.by("createdAt").descending())).endsWith("a.id desc");
    }

    @Test
    void an_unmapped_sort_property_throws_rather_than_being_dropped() {
        assertThatThrownBy(() -> service.orderByClause(Sort.by("ipAddress").ascending()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ipAddress");
    }

    /**
     * The controller's whitelist is the only thing between a raw {@code sort}
     * parameter and the column map. If they drift, one of two things happens:
     * a key the controller allows blows up here at runtime, or a column this
     * map offers is unreachable. Neither is visible without this check.
     */
    @Test
    @SuppressWarnings("unchecked")
    void the_controllers_whitelist_and_the_column_map_hold_the_same_keys() throws Exception {
        Field field = AuditLogQueryServiceImpl.class.getDeclaredField("SORT_COLUMNS");
        field.setAccessible(true);
        Set<String> mapped = ((Map<String, String>) field.get(null)).keySet();

        assertThat(mapped).containsExactlyInAnyOrderElementsOf(AuditLogController.SORTABLE);
    }
}
