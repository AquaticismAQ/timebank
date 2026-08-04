/**
 * Import jobs, validation, commit, export jobs (B5.9-B5.11, D5).
 *
 * The `job` table (V10) is this module's one shared resource; every
 * background run is a dedicated-executor {@code @Async} method claimed
 * and checkpointed through {@code JobProgressWriter}, never inline in the
 * request that created it.
 */
package cn.timebank.dataexchange;
