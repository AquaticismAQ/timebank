package cn.timebank.iam.application.dto;

import java.io.Serializable;
import java.util.Set;

/**
 * What the {@code security} module needs to authenticate a principal and
 * build its security context — a DTO, not the {@code User} entity, per
 * Design.md §6's module-boundary rule (ArchUnit-enforced, B0.2).
 *
 * {@code Serializable}: this ends up inside the {@code SecurityContext}
 * Spring Session JDBC stores in {@code SPRING_SESSION_ATTRIBUTES} — its
 * default converter uses Java serialization, so every object reachable
 * from the session (including this one) must support it, not just the
 * top-level {@code Authentication}.
 */
public record AuthenticatedUserView(
        String id,
        String username,
        String passwordHash,
        String displayName,
        boolean active,
        boolean locked,
        String preferredLanguage,
        Set<String> roleCodes,
        Set<String> permissionCodes,
        String studentId,
        int sessionTimeoutMinutes) implements Serializable {
}
