/**
 * Per-student progress projection (B3.11) — a stored *lifetime* total,
 * updated only from inside the approval transaction with amounts already
 * resolved against the rule in effect at each record's own service date
 * (invariant 8). Annual figures are computed live, bounded to one student
 * and one academic year, not stored as a second stateful counter.
 */
package cn.timebank.progress;
