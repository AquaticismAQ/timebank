package cn.timebank.notification;

import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.CreateUserRequest;
import cn.timebank.iam.application.dto.UserDto;
import cn.timebank.notification.application.NotificationService;
import cn.timebank.notification.application.dto.NotificationDto;
import cn.timebank.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5.4/B5.5's exit criteria against a real embedded server: unread-count
 * accuracy, mark-read/mark-all-read, and a real {@code 304} — {@code
 * ShallowEtagHeaderFilter} only activates on the real servlet pipeline, so
 * this can't be proven by calling {@code NotificationService} directly the
 * way most other IT tests do.
 */
class NotificationReadPathIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private NotificationService notificationService;

    @Test
    void unreadCountIsAccurateAndReturnsARealNotModifiedOnceMarkedRead() {
        UserDto user = createUser("notif_read_1");
        notificationService.notify(user.id(), "case.resolved", "case", "case_fake");
        String sessionCookie = login(user.username(), "InitialPass123!");

        ResponseEntity<String> first = getWithSession("/api/v1/notifications/unread-count", sessionCookie);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("\"unreadCount\":1");
        String etag = first.getHeaders().getETag();
        assertThat(etag).as("ShallowEtagHeaderFilter must set an ETag").isNotNull();

        // Same content, same ETag -> 304, no change.
        ResponseEntity<String> replay = getWithSessionAndIfNoneMatch("/api/v1/notifications/unread-count", sessionCookie, etag);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        NotificationDto notification = notificationService.listForUser(user.id(), PageRequest.of(0, 10), null, null, "zh-CN")
                .getContent().get(0);
        String csrfCookie = fetchCsrfCookie();
        HttpHeaders patchHeaders = new HttpHeaders();
        patchHeaders.add(HttpHeaders.COOKIE, sessionCookie + "; " + csrfCookie);
        patchHeaders.add("X-XSRF-TOKEN", cookieValue(csrfCookie));
        restTemplate.exchange("/api/v1/notifications/" + notification.id(), HttpMethod.PATCH,
                new HttpEntity<>(patchHeaders), String.class);

        // Content changed (0 unread now) -> the same If-None-Match must no longer match -> 200 with a different ETag.
        ResponseEntity<String> afterRead = getWithSessionAndIfNoneMatch("/api/v1/notifications/unread-count", sessionCookie, etag);
        assertThat(afterRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterRead.getBody()).contains("\"unreadCount\":0");
        assertThat(afterRead.getHeaders().getETag()).isNotEqualTo(etag);
    }

    @Test
    void markAllReadClearsEveryUnreadNotificationInOneCall() {
        UserDto user = createUser("notif_read_2");
        notificationService.notify(user.id(), "case.resolved", "case", "case_fake_1");
        notificationService.notify(user.id(), "case.rejected", "case", "case_fake_2");
        assertThat(notificationService.unreadCount(user.id())).isEqualTo(2);

        String sessionCookie = login(user.username(), "InitialPass123!");
        String csrfCookie = fetchCsrfCookie();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie + "; " + csrfCookie);
        headers.add("X-XSRF-TOKEN", cookieValue(csrfCookie));
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/notifications/read-all", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(notificationService.unreadCount(user.id())).isZero();
    }

    @Test
    void listForUserFiltersByReadStatusAndStaysNewestFirstWithNoExplicitSort() throws InterruptedException {
        UserDto user = createUser("notif_read_3");
        notificationService.notify(user.id(), "case.resolved", "case", "case_fake_older");
        Thread.sleep(5); // guarantee a distinct createdAt from the previous notify()
        notificationService.notify(user.id(), "case.rejected", "case", "case_fake_newer");

        List<NotificationDto> unfiltered = notificationService.listForUser(user.id(), PageRequest.of(0, 10), null, null, "zh-CN")
                .getContent();
        assertThat(unfiltered).hasSize(2);
        // No explicit sort was requested; the service must still order newest-first.
        assertThat(unfiltered.get(0).relatedResourceId()).isEqualTo("case_fake_newer");
        assertThat(unfiltered.get(1).relatedResourceId()).isEqualTo("case_fake_older");

        NotificationDto older = unfiltered.get(1);
        notificationService.markRead(older.id(), user.id(), "zh-CN");

        List<NotificationDto> unreadOnly = notificationService.listForUser(user.id(), PageRequest.of(0, 10), "false", null, "zh-CN")
                .getContent();
        assertThat(unreadOnly).hasSize(1);
        assertThat(unreadOnly.get(0).relatedResourceId()).isEqualTo("case_fake_newer");

        List<NotificationDto> readOnly = notificationService.listForUser(user.id(), PageRequest.of(0, 10), "true", null, "zh-CN")
                .getContent();
        assertThat(readOnly).hasSize(1);
        assertThat(readOnly.get(0).relatedResourceId()).isEqualTo("case_fake_older");
    }

    /**
     * I3.6: the exact bug being fixed — a notification created once must
     * render in *either* language depending on who's reading it and in
     * which language, not the language in effect when it was created.
     * Directly through the real HTTP `Accept-Language` path (contract
     * §2.10), not just the service layer, since the language resolution
     * happens in the controller.
     */
    @Test
    void sameNotificationRendersInWhicheverLanguageTheReaderRequests() {
        UserDto user = createUser("notif_read_4");
        notificationService.notify(user.id(), "case.resolved", "case", "case_fake_lang");
        String sessionCookie = login(user.username(), "InitialPass123!");

        ResponseEntity<String> zh = getWithSessionAndLanguage("/api/v1/notifications", sessionCookie, "zh-CN");
        ResponseEntity<String> en = getWithSessionAndLanguage("/api/v1/notifications", sessionCookie, "en");

        assertThat(zh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(en.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Same underlying row (same id, same templateCode) — different rendered text.
        assertThat(zh.getBody()).isNotEqualTo(en.getBody());
        assertThat(zh.getBody()).doesNotContain(extractField(en.getBody(), "title"));
    }

    private ResponseEntity<String> getWithSessionAndLanguage(String path, String sessionCookie, String acceptLanguage) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        headers.add(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":\"");
        assertThat(idx).as(field + " present in " + json).isGreaterThanOrEqualTo(0);
        int start = idx + field.length() + 4;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private UserDto createUser(String username) {
        return userService.create(new CreateUserRequest(username, "InitialPass123!", "Display " + username,
                null, "student", "zh-CN"), "system");
    }

    private String login(String username, String password) {
        String csrfCookie = fetchCsrfCookie();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, csrfCookie);
        headers.add("X-XSRF-TOKEN", cookieValue(csrfCookie));
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/login", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookie = extractCookie(response, "TIMEBANK_SESSION");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private String fetchCsrfCookie() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/auth/csrf", String.class);
        return extractCookie(response, "XSRF-TOKEN");
    }

    private ResponseEntity<String> getWithSession(String path, String sessionCookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> getWithSessionAndIfNoneMatch(String path, String sessionCookie, String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, sessionCookie);
        headers.add(HttpHeaders.IF_NONE_MATCH, etag);
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
