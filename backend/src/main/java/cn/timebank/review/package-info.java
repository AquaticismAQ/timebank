/**
 * Review queue (a hand-written JdbcTemplate projection, B3.6), append-only
 * decisions (B3.7), assignment, and the approval transaction itself
 * (B3.12/B3.13, {@link cn.timebank.review.application.ReviewDecisionTransaction})
 * — review event + record status + (on approval) account transaction +
 * progress + notification, atomically, invariant 5.
 */
package cn.timebank.review;
