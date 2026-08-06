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
     */
    public record UserSummary(String id, String displayName, Set<String> roles, Set<String> permissions, String studentId) {
    }

    /** Backed by B2.11's `feature_flag` table (`organization.application.FeatureFlagService`). */
    public record FeatureFlags(boolean pov, boolean redemption, boolean borrowing, boolean appreciation) {
    }
}
