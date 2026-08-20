package cn.timebank.security.application;

import java.util.Set;

/**
 * B1.5: the pluggable principal contract every layer of the app codes
 * against, instead of Spring Security's {@code Authentication}/{@code
 * UserDetails} directly. The cookie-session path
 * ({@code CookieSessionUserDetails}) is the only implementation today.
 * Phase 3's opaque bearer token for the Mini Program (Design.md §D4
 * caveat) is designed to be a second implementation plus a second {@code
 * AuthenticationProvider} — an addition, not a change to this interface or
 * to any controller.
 */
public interface TimeBankPrincipal {

    String id();

    String displayName();

    Set<String> roleCodes();

    Set<String> permissionCodes();

    String preferredLanguage();

    /** Null for staff; the linked student record's id for a student principal (still opaque — never parsed). */
    String studentId();

    default boolean hasPermission(String code) {
        return permissionCodes().contains(code);
    }

    /**
     * 07-RoleFinetune.md §2.1's original rule ("every role except the
     * protected {@code student} role gets unrestricted school-wide
     * visibility") was revisited 2026-08-20 (§2.10) — see {@link
     * #hasSchoolWideVisibility()} for the narrower, data-VISIBILITY-specific
     * replacement most call sites now use.
     *
     * This method keeps its original "any non-student" meaning and stays in
     * use for {@code isManager()}/{@code isAdmin()}-style *authorization*
     * checks (can this account confirm a registration beyond its own, see
     * a draft project it doesn't own, reassign a project's owner) and for
     * import/export job visibility ("can this account see every job, not
     * just its own") — none of those are grade-scoped concepts (a project,
     * an import job, has no grade), so narrowing them to
     * {@link #hasSchoolWideVisibility()} would just make them stricter
     * (own-only) with no corresponding "within assigned grades" benefit,
     * and §2.10 didn't ask for that. The name is admittedly a bit of a
     * misnomer now that it no longer governs most *data* visibility — kept
     * rather than renamed, to keep §2.10's diff focused on what was
     * actually asked.
     */
    default boolean hasFullDataScope() {
        return !roleCodes().contains("student");
    }

    /**
     * 07-RoleFinetune.md §2.10 (2026-08-20): the replacement for {@link
     * #hasFullDataScope()} at every {@code DataScopeResolver} call site that
     * resolves *grade-scoped* data (service records, event proposals,
     * cases, students, accounts, and the dashboard/file/evidence reads that
     * key off those) — reverting §2.1's blanket "any non-student sees
     * everything" back to what was true before it: only the two
     * structurally school-wide roles (07-RoleFinetune.md §2.6.2's
     * {@code admin}/{@code sysadmin}, the only roles exempt from needing a
     * grade assignment at all) get unrestricted visibility. {@code teacher}
     * (and any future grade-scoped custom role) is limited to its assigned
     * grades via {@code TeacherScopeAssignmentService}/{@code
     * DataScopeResolver}'s {@code Grades} branch, exactly as it was before
     * §2.1 — that mechanism was never deleted, just made moot while
     * {@code hasFullDataScope()} always short-circuited to {@code All}
     * first.
     */
    default boolean hasSchoolWideVisibility() {
        return roleCodes().contains("admin") || roleCodes().contains("sysadmin");
    }
}
