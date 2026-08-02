package cn.timebank.iam.application.dto;

import java.util.Set;

/** Contract §2.2's login-response shape, reused verbatim by `GET /me`. */
public record MeResponse(UserSummary user, Set<String> workspaces, String preferredLanguage, FeatureFlags featureFlags) {

    public record UserSummary(String id, String displayName, Set<String> roles, Set<String> permissions) {
    }

    /**
     * Static defaults until B2.11 builds the real `feature_flag` table —
     * every Phase 3/4 capability off, per Design.md §14 (nothing flagged is
     * half-built).
     */
    public record FeatureFlags(boolean pov, boolean redemption, boolean borrowing, boolean appreciation) {
        public static FeatureFlags allDisabled() {
            return new FeatureFlags(false, false, false, false);
        }
    }
}
