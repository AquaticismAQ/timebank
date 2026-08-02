package cn.timebank.iam.application.dto;

import java.util.Set;

/** Contract §2.2's login-response shape, reused verbatim by `GET /me`. */
public record MeResponse(UserSummary user, Set<String> workspaces, String preferredLanguage, FeatureFlags featureFlags) {

    public record UserSummary(String id, String displayName, Set<String> roles, Set<String> permissions) {
    }

    /** Backed by B2.11's `feature_flag` table (`organization.application.FeatureFlagService`). */
    public record FeatureFlags(boolean pov, boolean redemption, boolean borrowing, boolean appreciation) {
    }
}
