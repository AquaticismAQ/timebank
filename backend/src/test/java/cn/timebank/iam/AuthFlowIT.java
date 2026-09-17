package cn.timebank.iam;

import cn.timebank.iam.application.ActivationService;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UpdateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.iam.domain.User;
import cn.timebank.iam.infrastructure.UserRepository;
import cn.timebank.security.application.SessionRevocationService;
import cn.timebank.security.application.TeacherScopeAssignmentService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1.3's exit criteria proven against a real embedded server, real cookies,
 * and real MySQL — not slice tests, since session fixation defense, CSRF,
 * and revocation are exactly the kind of behaviour that only shows up
 * end to end.
 */
class AuthFlowIT extends AbstractIntegrationTest {

    /** One distinct client IP per activation — see {@link #createUser}. */
    private static final AtomicInteger ACTIVATION_IP_SEQ = new AtomicInteger();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private ActivationService activationService;

    @Autowired
    private SessionRevocationService sessionRevocationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeacherScopeAssignmentService teacherScopeAssignmentService;

    /**
     * `/me`'s `studentId` (added 2026-08-06, `Docs/implementation/00-Status.md`
     * §30) — present for a student account with a linked student record,
     * `null` for a staff account with none.
     */
    @Test
    void meReturnsStudentIdForALinkedStudentAccountAndNullForStaff() {
        UserDto student = createUser("auth_flow_student", "InitialChoice123!", "student");
        User entity = userRepository.findById(student.id()).orElseThrow();
        entity.setStudentId("std_auth_flow_test");
        userRepository.save(entity);
        String studentSession = login("auth_flow_student", "InitialChoice123!");
        assertThat(getWithSession("/api/v1/me", studentSession).getBody()).contains("\"studentId\":\"std_auth_flow_test\"");

        createUser("auth_flow_teacher", "InitialChoice123!", "teacher");
        String teacherSession = login("auth_flow_teacher", "InitialChoice123!");
        ResponseEntity<String> teacherMe = getWithSession("/api/v1/me", teacherSession);
        // Global Jackson config omits null fields from the wire entirely
        // (non_null inclusion) rather than rendering "studentId":null — see
        // the AdminStatsDto.reviewAvgHours finding, `00-Status.md` I1
        // notes — so "absent" is what a null studentId actually looks like.
        assertThat(teacherMe.getBody()).doesNotContain("\"studentId\":\"");
    }

    /**
     * A scoped teacher's {@code scopeGradeIds} must be on the login/refresh
     * response, not just {@code GET /me} — the frontend never calls
     * {@code api.auth.me()} after establishing a session (it seeds the
     * whole-session user object straight from the login/refresh response),
     * so a grade filter driven only by the login payload silently showed
     * every grade instead of the teacher's own (found live 2026-08-10,
     * root cause: {@code AuthController} skipped the enrichment
     * {@code MeController} already applies).
     */
    @Test
    void loginAndRefreshBothCarryTheTeachersScopeGradeIds() {
        UserDto teacher = createUser("auth_flow_scoped_teacher", "InitialChoice123!", "teacher");
        teacherScopeAssignmentService.replaceGradeScope(teacher.id(), Set.of("grade_10"));

        String csrfCookie = fetchCsrfCookie();
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        loginHeaders.add(HttpHeaders.COOKIE, csrfCookie);
        loginHeaders.add("X-XSRF-TOKEN", cookieValue(csrfCookie));
        String loginBody = "{\"username\":\"auth_flow_scoped_teacher\",\"password\":\"InitialChoice123!\"}";
        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login", new HttpEntity<>(loginBody, loginHeaders), String.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).contains("\"scopeGradeIds\":[\"grade_10\"]");

        String sessionCookie = extractCookie(loginResponse, "TIMEBANK_SESSION");
        HttpHeaders refreshHeaders = new HttpHeaders();
        refreshHeaders.add(HttpHeaders.COOKIE, sessionCookie + "; " + csrfCookie);
        refreshHeaders.add("X-XSRF-TOKEN", cookieValue(csrfCookie));
        ResponseEntity<String> refreshResponse = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new HttpEntity<>(refreshHeaders), String.class);
        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshResponse.getBody()).contains("\"scopeGradeIds\":[\"grade_10\"]");
    }

    @Test
    void loginSucceedsAndEstablishesAWorkingSession() {
        createUser("auth_flow_1", "InitialChoice123!", "student");

        String sessionCookie = login("auth_flow_1", "InitialChoice123!");

        ResponseEntity<String> me = getWithSession("/api/v1/me", sessionCookie);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).contains("\"student\"").contains("Display auth_flow_1");
    }

    @Test
    void wrongPasswordIsRejectedAndLocksAfterThreshold() {
        createUser("auth_flow_2", "InitialChoice123!", "student");

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> attempt = loginRaw("auth_flow_2", "WrongPassword!");
            assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // The 6th attempt, even with the CORRECT password, must now be rejected — locked.
        ResponseEntity<String> lockedAttempt = loginRaw("auth_flow_2", "InitialChoice123!");
        assertThat(lockedAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deactivatingAUserRevokesItsLiveSessionImmediately() {
        UserDto user = createUser("auth_flow_3", "InitialChoice123!", "student");
        String sessionCookie = login("auth_flow_3", "InitialChoice123!");

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
        createUser("auth_flow_4", "InitialChoice123!", "student");
        String sessionCookie = login("auth_flow_4", "InitialChoice123!");
        assertThat(getWithSession("/api/v1/me", sessionCookie).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Simulates what UserController does after userService.changeOwnPassword(...) succeeds.
        userService.changeOwnPassword(
                userService.findByUsernameForAuth("auth_flow_4").orElseThrow().id(),
                "InitialChoice123!", "NewChoice456!Ab");
        sessionRevocationService.revokeAllSessionsForUser("auth_flow_4");

        ResponseEntity<String> afterChange = getWithSession("/api/v1/me", sessionCookie);
        assertThat(afterChange.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The new password logs in fine, proving the change actually took effect.
        String newSessionCookie = login("auth_flow_4", "NewChoice456!Ab");
        assertThat(getWithSession("/api/v1/me", newSessionCookie).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Creates an account and puts it through the real activation flow, because
     * since issue 096 that is the <em>only</em> way any account — student or
     * staff — acquires a password. {@code userService.create} now leaves it
     * {@code PENDING_ACTIVATION} with no hash, so a test that skipped this step
     * would be testing login against an account that cannot log in.
     *
     * <p>Each redemption is charged to its own client IP: the per-IP attempt
     * limit in {@code ActivationThrottle} is real and shared across the class,
     * and a fixture that quietly exhausted it would start failing tests for a
     * reason having nothing to do with what they assert.</p>
     */
    private UserDto createUser(String username, String password, String roleCode) {
        UserDto created = userService.create(new CreateUserRequest(username, "Display " + username,
                username + "@example.com", roleCode, "zh-CN"), "system");
        activationService.redeem(
                activationService.issue(created.id(), "system").code(), password,
                "198.51.100." + ACTIVATION_IP_SEQ.incrementAndGet());
        return created;
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
