package cn.timebank.project.application.dto;

import java.time.Instant;

/**
 * One event in a project's life (issue 158).
 *
 * <p>{@code action} is the raw audit action code — {@code project.publish},
 * {@code enrollment.register} — never a sentence. The backend authors no
 * localized copy, so the server sends the code and the client supplies the
 * words, which is the same division {@code AuditLogDto} already follows and the
 * reason this record no longer carries the {@code "Project created"} literal it
 * used to.</p>
 *
 * @param actorId     null for a system-generated event, such as the sweep that
 *                    archives a project nobody registered for. Distinct from an
 *                    actor whose account has since been deleted, where the id
 *                    survives and the name does not.
 * @param actorName   resolved server-side, from the same statement that read the
 *                    row; null when there is no actor or no account left.
 * @param detail      the audit row's reason code or {@code after} summary when it
 *                    carries one — a cancellation reason, a new status. Null for
 *                    the events that are fully described by their action.
 * @param subject     whose registration or service record this event is about.
 *                    Null for the project's own events, which are about the
 *                    project itself. Needed because the actor on a review or a
 *                    confirmation is the <i>staff member</i>, so without it
 *                    "Record approved — Teacher Wei Chen" never says whose.
 */
public record TimelineEntryDto(String action, String actorId, String actorName, String subject, String detail,
                                Instant occurredAt) {
}
