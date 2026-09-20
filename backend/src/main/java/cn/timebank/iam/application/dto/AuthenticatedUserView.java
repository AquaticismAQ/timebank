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
        int sessionTimeoutMinutes,
        // Nullable: `user.email` has no NOT NULL constraint and
        // CreateUserRequest.email deliberately no @NotBlank (issue 096 §1).
        // Carried here only so evidence delivery can name the downloader as a
        // person rather than a login (issue 123) — it is not part of any API
        // response. Adding it did not invalidate sessions open across its
        // deploy: a record class's serialVersionUID is 0L unless declared, so
        // an older blob in SPRING_SESSION_ATTRIBUTES deserializes with this
        // field null rather than failing.
        String email) implements Serializable {

    /**
     * The pre-{@code email} signature, kept so that adding an optional field
     * used by one delivery path did not have to touch 23 construction sites.
     * {@code null} is a legitimate value here, not a stand-in: plenty of real
     * accounts have no email.
     */
    public AuthenticatedUserView(String id, String username, String passwordHash, String displayName,
                                 boolean active, boolean locked, String preferredLanguage,
                                 Set<String> roleCodes, Set<String> permissionCodes, String studentId,
                                 int sessionTimeoutMinutes) {
        this(id, username, passwordHash, displayName, active, locked, preferredLanguage,
                roleCodes, permissionCodes, studentId, sessionTimeoutMinutes, null);
    }
}
