package cn.timebank.security.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Re-seats the signed-in session's authorities from the database.
 *
 * <p><b>Why this exists.</b> Authorities are permission codes carried on
 * {@link CookieSessionUserDetails}, which is serialized into the session row at
 * login and read back on every subsequent request. They are therefore a
 * <i>snapshot taken when the session began</i>: a permission granted afterwards
 * is not in it, and a permission revoked afterwards is still in it, for as long
 * as that session lives — eight hours for staff.</p>
 *
 * <p>That was invisible until permissions started deciding what a screen shows.
 * Adding {@code serviceCategories.read} (issue 157) produced the visible form:
 * the browser learned about the new permission and offered the Service
 * Categories tab, while the session it was talking to had never heard of it, so
 * the management read behind that tab answered {@code 403} and the screen said
 * "0 service categories" to people who hold every permission there is.</p>
 *
 * <p>The client re-reads {@code /me} on every app load for the same reason. This
 * is the server half of that, invoked from the same call: the two then move
 * together by construction instead of drifting apart, and {@code /me} already
 * loads the permissions from the database, so the refresh costs one extra query
 * on a request that happens once per page load.</p>
 *
 * <h2>What it does not do</h2>
 *
 * <p>No revalidation of the account itself. If the user has been disabled or
 * locked since login, the session is left exactly as it was rather than being
 * torn down here — that is a different decision with a different blast radius,
 * and quietly attaching it to a tab-visibility fix would be the wrong place to
 * make it.</p>
 *
 * <p>Nothing is written when the authorities have not changed, which is the
 * ordinary case: rewriting the session blob on every page load would turn a
 * read into a write for no gain.</p>
 */
@Component
public class SessionAuthorityRefresher {

    private static final Logger log = LoggerFactory.getLogger(SessionAuthorityRefresher.class);

    /**
     * The default repository Spring Security uses when no custom one is
     * configured — the session it writes to is the Spring Session JDBC-backed
     * {@code HttpSession}, so the new principal lands in the same row the next
     * request reads.
     */
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    private final UserDetailsService userDetailsService;

    SessionAuthorityRefresher(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    /** @return true when the session's authorities were actually replaced. */
    public boolean refresh(Authentication current, HttpServletRequest request, HttpServletResponse response) {
        if (current == null || !(current.getPrincipal() instanceof CookieSessionUserDetails principal)) {
            return false;
        }
        try {
            UserDetails fresh = userDetailsService.loadUserByUsername(principal.getUsername());
            Set<String> held = codesOf(current.getAuthorities());
            Set<String> actual = codesOf(fresh.getAuthorities());
            if (held.equals(actual)) {
                return false;
            }

            Authentication refreshed = UsernamePasswordAuthenticationToken.authenticated(
                    fresh, null, fresh.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(refreshed);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            // Worth a line: it means somebody's role changed under a live
            // session, which is rare and is exactly what you want to see in the
            // log when a permission question comes in.
            log.info("Refreshed session authorities for user {}: {} granted, {} revoked",
                    principal.id(), difference(actual, held), difference(held, actual));
            return true;
        } catch (RuntimeException e) {
            // A missing or unreadable account leaves the session as it was. The
            // 401 path already handles a session that is genuinely finished;
            // failing a page load over a refresh would be worse than staleness.
            log.warn("Could not refresh session authorities for user {}: {}", principal.id(), e.toString());
            return false;
        }
    }

    private static Set<String> codesOf(java.util.Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        return a.stream().filter(code -> !b.contains(code)).collect(Collectors.toCollection(java.util.TreeSet::new));
    }
}
