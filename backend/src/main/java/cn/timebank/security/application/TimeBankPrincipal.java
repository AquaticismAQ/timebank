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
}
