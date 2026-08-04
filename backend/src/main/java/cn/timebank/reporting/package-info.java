/**
 * Dashboards, statistics, report queries (B5 Pass 2).
 *
 * JdbcTemplate end to end, never JPA entities (Design.md D6) — this
 * module reads other modules' tables directly for aggregation, which is
 * the one legitimate exception to "go through the owning module's
 * service" (Design.md §6's read-model-query clause), the same precedent
 * `review`'s `ReviewQueueRepository` (B3.6) established.
 */
package cn.timebank.reporting;
