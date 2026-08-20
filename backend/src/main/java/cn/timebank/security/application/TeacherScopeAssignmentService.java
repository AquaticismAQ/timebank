package cn.timebank.security.application;

import java.util.Set;

/**
 * B1.7's write side — creates/replaces the grade-scope rows a teacher
 * account is bonded to, across every resource type {@link DataScopeResolver}
 * is wired to today. Read side is {@link DataScopeResolver} itself; this
 * exists so {@code iam}'s account-creation/edit flow (issue 002) can write
 * these rows without reaching into {@code security}'s domain/infrastructure
 * layers directly (ArchUnit's module-boundary rule) — the same
 * cross-module-via-application-package pattern already used for
 * {@link SessionRevocationService}.
 */
public interface TeacherScopeAssignmentService {

    /** The grade ids a user is currently bonded to (empty if none, e.g. not a teacher). */
    Set<String> gradesOf(String userId);

    /**
     * 07-RoleFinetune.md §2.10: the reverse of {@link #gradesOf} — every user id bonded to
     * {@code gradeId} for {@code resourceType}. Used where a caller needs "who covers this
     * grade" rather than "which grades does this user cover" — e.g. deciding who to notify
     * when a proposal is approved and its grade needs a publish-permission holder.
     */
    Set<String> userIdsAssignedToGrade(String resourceType, String gradeId);

    /**
     * Replaces every managed-resource-type grade assignment for
     * {@code userId} with {@code gradeIds} in one go — an empty set clears
     * the bond entirely (used when a teacher role is removed from a user).
     */
    void replaceGradeScope(String userId, Set<String> gradeIds);
}
