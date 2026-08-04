/**
 * B6: cohort retention purge, ledger reconciliation, log-partition and
 * short-lived-table lifecycle sweeps (Design.md §16.2, §16.3, D12).
 *
 * Deliberately cross-cutting rather than owned by any single business
 * module — the purge alone touches `servicerecord`, `account`, `review`,
 * `caseflow`, `enrollment`, `proposal`, `progress`, `file`, `notification`,
 * and `student`. Every write here is raw SQL against those modules' own
 * tables (the same legitimate read/write-model exception `reporting` and
 * `dataexchange` already establish, Design.md §6), except where a business
 * invariant genuinely needs the owning module's own service — `file`'s
 * object deletion goes through {@code FileService.forcePurge}, not a raw
 * `DELETE`, since only `file` knows how to also remove the storage bytes.
 */
package cn.timebank.lifecycle;
