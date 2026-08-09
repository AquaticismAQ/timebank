package cn.timebank.iam.application.dto;

import java.util.Set;

/** Contract §2.2's login-response shape, reused verbatim by `GET /me`. */
public record MeResponse(UserSummary user, Set<String> workspaces, String preferredLanguage, FeatureFlags featureFlags) {

    /**
     * {@code studentId} is {@code null} for staff accounts (teacher/admin/
     * sysadmin) — same nullability as {@link cn.timebank.iam.domain.User#getStudentId()}.
     * Added 2026-08-06 so the frontend can exclude "myself" from a
     * co-organizer search without a second round trip
     * (`Docs/implementation/00-Status.md` §30).
     *
     * {@code scopeGradeIds} (issue 004): the operator's own grade bond, so
     * an admin-portal filter dropdown can narrow itself to the grades a
     * scoped teacher can actually see, rather than listing the whole
     * school's grades. Empty for non-teacher accounts.
     */
    public record UserSummary(String id, String displayName, Set<String> roles, Set<String> permissions,
                               String studentId, Set<String> scopeGradeIds) {
    }

    /** Backed by B2.11's `feature_flag` table (`organization.application.FeatureFlagService`). */
    public record FeatureFlags(boolean pov, boolean redemption, boolean borrowing, boolean appreciation) {
    }

    /**
     * {@code UserServiceImpl} (module {@code iam}) can't populate {@code
     * scopeGradeIds} itself — same {@code security.application} ↔ {@code
     * iam.application} cycle {@code UserDto.withScopeGradeIds} avoids.
     * {@code MeController} enriches with this copy helper instead.
     */
    public MeResponse withScopeGradeIds(Set<String> scopeGradeIds) {
        return new MeResponse(
                new UserSummary(user.id(), user.displayName(), user.roles(), user.permissions(), user.studentId(), scopeGradeIds),
                workspaces, preferredLanguage, featureFlags);
    }
}
