/**
 * Project lifecycle (B4.1-B4.5). {@link cn.timebank.project.domain.ProjectStateMachine}
 * copies {@code ServiceRecordStateMachine}'s shape (B3.2's template).
 * {@code REGISTRATION_OPEN}/{@code IN_PROGRESS}/{@code COMPLETED} are
 * time-derived and synced lazily on read/action access — no ShedLock/cron
 * infrastructure exists yet in this codebase (see
 * Docs/implementation/00-Status.md §16).
 */
package cn.timebank.project;
