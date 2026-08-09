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
     * Replaces every managed-resource-type grade assignment for
     * {@code userId} with {@code gradeIds} in one go — an empty set clears
     * the bond entirely (used when a teacher role is removed from a user).
     */
    void replaceGradeScope(String userId, Set<String> gradeIds);
}
