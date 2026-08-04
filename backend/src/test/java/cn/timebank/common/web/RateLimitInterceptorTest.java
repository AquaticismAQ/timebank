package cn.timebank.common.web;

import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.security.application.CookieSessionUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B6.8: fast, no Spring context — a plain in-memory bucket per (route,
 * principal) key, exercised directly. The two-instance limitation itself
 * (an attacker doubling their allowance by alternating instances) is a
 * named, accepted design trade-off (D1), not something a single-JVM test
 * could prove either way.
 */
class RateLimitInterceptorTest {

    private final RateLimitInterceptor interceptor = new RateLimitInterceptor();

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsRequestsBelowTheLimitAndRejectsBeyondIt() {
        authenticateAs("usr_ratelimit_a");
        MockHttpServletResponse response = new MockHttpServletResponse();

        for (int i = 0; i < 5; i++) {
            assertThat(interceptor.preHandle(postTo("/api/v1/exports"), response, new Object())).isTrue();
        }
        // Exports are capped at 5/min — the 6th within the same window must be rejected.
        assertThatThrownBy(() -> interceptor.preHandle(postTo("/api/v1/exports"), response, new Object()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void limitsAreScopedPerPrincipalNotGlobal() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        authenticateAs("usr_ratelimit_b");
        for (int i = 0; i < 5; i++) {
            assertThat(interceptor.preHandle(postTo("/api/v1/exports"), response, new Object())).isTrue();
        }
        assertThatThrownBy(() -> interceptor.preHandle(postTo("/api/v1/exports"), response, new Object()))
                .isInstanceOf(ApiException.class);

        // A different principal has their own, untouched bucket.
        authenticateAs("usr_ratelimit_c");
        assertThat(interceptor.preHandle(postTo("/api/v1/exports"), response, new Object())).isTrue();
    }

    @Test
    void onlyThePostRouteConfiguredIsLimited() {
        authenticateAs("usr_ratelimit_d");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockHttpServletRequest getRequest = new MockHttpServletRequest("GET", "/api/v1/exports");
        for (int i = 0; i < 50; i++) {
            assertThat(interceptor.preHandle(getRequest, response, new Object())).isTrue();
        }
    }

    private MockHttpServletRequest postTo(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }

    private void authenticateAs(String userId) {
        AuthenticatedUserView view = new AuthenticatedUserView(
                userId, userId, "hash", "Test " + userId, true, false, "zh-CN", Set.of("admin"), Set.of(), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
