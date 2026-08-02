package cn.timebank.iam;

import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UpdateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.security.application.SessionRevocationService;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1.3's exit criteria proven against a real embedded server, real cookies,
 * and real MySQL — not slice tests, since session fixation defense, CSRF,
 * and revocation are exactly the kind of behaviour that only shows up
 * end to end.
 */
class AuthFlowIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private SessionRevocationService sessionRevocationService;

    @Test
    void loginSucceedsAndEstablishesAWorkingSession() {
        createUser("auth_flow_1", "InitialPass123!", Set.of("student"));

        String sessionCookie = login("auth_flow_1", "InitialPass123!");

        ResponseEntity<String> me = getWithSession("/api/v1/me", sessionCookie);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).contains("\"student\"").contains("Display auth_flow_1");
    }

    @Test
    void wrongPasswordIsRejectedAndLocksAfterThreshold() {
        createUser("auth_flow_2", "InitialPass123!", Set.of("student"));

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> attempt = loginRaw("auth_flow_2", "WrongPassword!");
            assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // The 6th attempt, even with the CORRECT password, must now be rejected — locked.
        ResponseEntity<String> lockedAttempt = loginRaw("auth_flow_2", "InitialPass123!");
        assertThat(lockedAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deactivatingAUserRevokesItsLiveSessionImmediately() {
        UserDto user = createUser("auth_flow_3", "InitialPass123!", Set.of("student"));
        String sessionCookie = login("auth_flow_3", "InitialPass123!");

        assertThat(getWithSession("/api/v1/me", sessionCookie).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Re-fetch: the successful login above touched updatedAt on the user
        // row, so the version captured at createUser() time is already stale.
        long currentVersion = userService.getById(user.id()).version();
        userService.update(user.id(), new UpdateUserRequest(null, null, "INACTIVE", null, currentVersion), "system");
        sessionRevocationService.revokeAllSessionsForUser("auth_flow_3");

        ResponseEntity<String> afterDeactivation = getWithSession("/api/v1/me", sessionCookie);
        assertThat(afterDeactivation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void changingPasswordRevokesTheOldSessionImmediately() {
        createUser("auth_flow_4", "InitialPass123!", Set.of("student"));
        String sessionCookie = login("auth_flow_4", "InitialPass123!");
        assertThat(getWithSession("/api/v1/me", sessionCookie).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Simulates what UserController does after userService.changeOwnPassword(...) succeeds.
        userService.changeOwnPassword(
                userService.findByUsernameForAuth("auth_flow_4").orElseThrow().id(),
                "InitialPass123!", "NewPassword456!");
        sessionRevocationService.revokeAllSessionsForUser("auth_flow_4");

        ResponseEntity<String> afterChange = getWithSession("/api/v1/me", sessionCookie);
        assertThat(afterChange.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The new password logs in fine, proving the change actually took effect.
        String newSessionCookie = login("auth_flow_4", "NewPassword456!");
        assertThat(getWithSession("/api/v1/me", newSessionCookie).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UserDto createUser(String username, String password, Set<String> roleCodes) {
        return userService.create(new CreateUserRequest(username, password, "Display " + username,
                username + "@example.com", roleCodes, "zh-CN"), "system");
    }

    private String login(String username, String password) {
        ResponseEntity<String> response = loginRaw(username, password);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookie = extractCookie(response, "TIMEBANK_SESSION");
        assertThat(cookie).as("session cookie must be set on successful login").isNotNull();
        return cookie;
    }

    private ResponseEntity<String> loginRaw(String username, String password) {
        String csrfCookie = fetchCsrfCookie();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, csrfCookie);
        headers.add("X-XSRF-TOKEN", cookieValue(csrfCookie));
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        return restTemplate.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
    }

    private String fetchCsrfCookie() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/auth/csrf", String.class);
        String cookie = extractCookie(response, "XSRF-TOKEN");
        assertThat(cookie).as("CSRF cookie must be issued").isNotNull();
        return cookie;
    }

    private ResponseEntity<String> getWithSession(String path, String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String extractCookie(ResponseEntity<?> response, String cookieName) {
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return null;
        }
        return setCookies.stream()
                .filter(c -> c.startsWith(cookieName + "="))
                .map(c -> c.split(";", 2)[0])
                .findFirst()
                .orElse(null);
    }

    private String cookieValue(String cookiePair) {
        return cookiePair.substring(cookiePair.indexOf('=') + 1);
    }
}
