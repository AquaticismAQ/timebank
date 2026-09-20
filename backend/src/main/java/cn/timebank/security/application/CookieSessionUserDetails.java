package cn.timebank.security.application;

import cn.timebank.iam.application.dto.AuthenticatedUserView;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.Set;

/**
 * The cookie-session implementation of {@link TimeBankPrincipal} (B1.5) —
 * also a Spring Security {@link UserDetails} so {@code DaoAuthenticationProvider}
 * can do the actual credential check. Authorities are permission codes
 * directly (e.g. {@code reviews.decide}), matching every {@code
 * @PreAuthorize("hasAuthority('...')")} in the codebase.
 *
 * {@code Serializable}: this is the {@code Authentication}'s principal,
 * stored in {@code SPRING_SESSION_ATTRIBUTES} via Java serialization
 * (Spring Session JDBC's default) — see {@link AuthenticatedUserView}'s
 * note. Missing this produced an opaque {@code ConversionFailedException}
 * deep inside session save, not an obvious error at the point of mistake.
 */
public class CookieSessionUserDetails implements UserDetails, TimeBankPrincipal, Serializable {

    /**
     * Pinned to the value the JVM computed for this class before {@link
     * #email()} was added (issue 123), because <b>the default is derived from
     * the method list, not just the fields</b>. Adding one accessor changed it
     * from {@code 848046325616438304} to {@code -394976465083591385}, which
     * made every blob already sitting in {@code SPRING_SESSION_ATTRIBUTES}
     * unreadable — measured, not assumed:
     *
     * <pre>InvalidClassException: local class incompatible:
     *   stream classdesc serialVersionUID = 848046325616438304,
     *   local class serialVersionUID = -394976465083591385</pre>
     *
     * Every logged-in user would have met that on the first request after the
     * deploy. Declaring the identifier is what makes an ordinary change to
     * this class an ordinary deploy; keep it, and do not "regenerate" it.
     * Removing or changing a <em>field</em> is the case that genuinely breaks
     * compatibility, and then a migration has to be thought about rather than
     * a number bumped.
     */
    private static final long serialVersionUID = 848046325616438304L;

    private final AuthenticatedUserView view;

    public CookieSessionUserDetails(AuthenticatedUserView view) {
        this.view = view;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return view.permissionCodes().stream().map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    public String getPassword() {
        return view.passwordHash();
    }

    @Override
    public String getUsername() {
        return view.username();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !view.locked();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return view.active();
    }

    @Override
    public String id() {
        return view.id();
    }

    @Override
    public String displayName() {
        return view.displayName();
    }

    @Override
    public Set<String> roleCodes() {
        return view.roleCodes();
    }

    @Override
    public Set<String> permissionCodes() {
        return view.permissionCodes();
    }

    @Override
    public String preferredLanguage() {
        return view.preferredLanguage();
    }

    @Override
    public String studentId() {
        return view.studentId();
    }

    public int sessionTimeoutMinutes() {
        return view.sessionTimeoutMinutes();
    }

    /**
     * Nullable — email is optional on every account (issue 106 §1). Kept off
     * {@link TimeBankPrincipal} on purpose, like {@link
     * #sessionTimeoutMinutes()}: that interface is the contract Phase 3's
     * second principal must also satisfy, and an optional field that one
     * delivery path formats (issue 123's evidence watermark) is not part of
     * it.
     */
    public String email() {
        return view.email();
    }
}
