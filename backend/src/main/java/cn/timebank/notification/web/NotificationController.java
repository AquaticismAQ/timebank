package cn.timebank.notification.web;

import cn.timebank.common.pagination.PageQuery;
import cn.timebank.common.pagination.PageableFactory;
import cn.timebank.common.web.ApiResponse;
import cn.timebank.common.web.PageMeta;
import cn.timebank.notification.application.AnnouncementService;
import cn.timebank.notification.application.NotificationService;
import cn.timebank.notification.application.dto.AnnouncementDto;
import cn.timebank.notification.application.dto.NotificationDto;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.security.application.CookieSessionUserDetails;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Contract §4.1. Every endpoint here is caller-scoped to their own notifications — no permission code, just `isAuthenticated()`. */
@RestController
public class NotificationController {

    private static final Set<String> SORTABLE = Set.of("createdAt");

    private final NotificationService notificationService;
    private final AnnouncementService announcementService;
    private final RequestLanguageService requestLanguageService;

    public NotificationController(NotificationService notificationService, AnnouncementService announcementService,
                                   RequestLanguageService requestLanguageService) {
        this.notificationService = notificationService;
        this.announcementService = announcementService;
        this.requestLanguageService = requestLanguageService;
    }

    @GetMapping("/api/v1/notifications")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<NotificationDto>> list(PageQuery query, Authentication auth) {
        Pageable pageable = PageableFactory.toPageable(query, SORTABLE);
        Page<NotificationDto> page = notificationService.listForUser(principalId(auth), pageable);
        return ApiResponse.ofList(page.getContent(), PageMeta.of(page));
    }

    /**
     * B5.5's "cheap polled endpoint" — a hand-computed {@code ETag} over the
     * count itself, not {@code ShallowEtagHeaderFilter} hashing the whole
     * response body: every response in this API is wrapped in an envelope
     * carrying a per-request {@code requestId} (contract §2.5), which makes
     * two responses with an identical {@code unreadCount} never byte-identical
     * — a whole-body hash would defeat the cache on every single request
     * (caught live: the filter set an {@code ETag} but a replayed {@code
     * If-None-Match} never matched it). Computing the {@code ETag} from just
     * the count is both correct and the only part of the response that
     * actually needs to be "unchanged" for a {@code 304}.
     */
    @GetMapping("/api/v1/notifications/unread-count")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch, Authentication auth) {
        long count = notificationService.unreadCount(principalId(auth));
        String etag = "\"unread-" + count + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(ApiResponse.of(Map.of("unreadCount", count)));
    }

    @PatchMapping("/api/v1/notifications/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<NotificationDto> markRead(@PathVariable String id, Authentication auth) {
        return ApiResponse.of(notificationService.markRead(id, principalId(auth)));
    }

    @PostMapping("/api/v1/notifications/read-all")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> markAllRead(Authentication auth) {
        notificationService.markAllRead(principalId(auth));
        return ApiResponse.of(null);
    }

    @GetMapping("/api/v1/announcements")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<AnnouncementDto>> announcements(
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage, Authentication auth) {
        String preferred = auth.getPrincipal() instanceof CookieSessionUserDetails p ? p.preferredLanguage() : null;
        return ApiResponse.of(announcementService.listActive(requestLanguageService.resolve(acceptLanguage, preferred)));
    }

    private String principalId(Authentication auth) {
        return ((CookieSessionUserDetails) auth.getPrincipal()).id();
    }
}
