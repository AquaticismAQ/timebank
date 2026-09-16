package cn.timebank.iam.web;

import cn.timebank.iam.application.CredentialSlipService;
import cn.timebank.iam.application.CredentialSlipService.ResetSlip;
import cn.timebank.iam.application.CredentialSlipService.SlipActor;
import cn.timebank.iam.application.CredentialSlipService.SlipDocument;
import cn.timebank.iam.application.dto.CredentialSlipDtos.IssueSlipsRequest;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.SessionRevocationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Every path by which a credential slip is produced — a class batch (contract
 * §4.15, 10-StudentIdentityAndOnboarding.md §3.4) and an admin password reset
 * (§3.7). §3.7 folds the second into the first deliberately: "one flow, one
 * document format, and still no server-generated password anywhere".
 *
 * <p><b>POST, not GET</b>, even though it downloads a file: the call mints a
 * fresh activation code for every account it prints, retiring whatever code
 * they held before. A GET that changes state would be retried by a proxy, a
 * prefetcher or a browser's back button, and each retry would invalidate the
 * slips just handed out.</p>
 *
 * <p>The response is the PDF itself, not the {@code data}/{@code meta}
 * envelope — the same exception §2.5 already makes for
 * {@code GET /files/{id}/content}. A failure before the document exists still
 * comes back as a normal error envelope from {@code GlobalExceptionHandler},
 * so a client distinguishes the two by content type.</p>
 */
@RestController
@RequestMapping("/api/v1/users")
public class CredentialSlipController {

    /**
     * §2.10, D-9/D-10. A role check, not a permission check, and deliberately
     * not a data-scope resolver. {@code users.manage} is held by exactly these
     * two roles today, so a permission-only gate would look identical and
     * silently widen the moment a school defines a custom staff role holding
     * it — which is precisely the "second, wider population of accounts that
     * can mint logins for students" §2.10 exists to prevent. Same shape as
     * {@code PATCH /school/info}'s {@code sysadmin} check (contract §4.16).
     */
    private static final Set<String> SLIP_ISSUING_ROLES = Set.of("admin", "sysadmin");

    private final CredentialSlipService credentialSlipService;
    private final RequestLanguageService requestLanguageService;
    private final SessionRevocationService sessionRevocationService;

    public CredentialSlipController(CredentialSlipService credentialSlipService,
                                     RequestLanguageService requestLanguageService,
                                     SessionRevocationService sessionRevocationService) {
        this.credentialSlipService = credentialSlipService;
        this.requestLanguageService = requestLanguageService;
        this.sessionRevocationService = sessionRevocationService;
    }

    @PostMapping(value = "/activation-slips", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('users.manage')")
    public ResponseEntity<byte[]> issueSlips(
            @Valid @RequestBody IssueSlipsRequest request,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        CookieSessionUserDetails principal = (CookieSessionUserDetails) auth.getPrincipal();
        String language = requestLanguageService.resolve(acceptLanguage, principal.preferredLanguage());

        return pdfDownload(credentialSlipService.issueForClass(request, actorOf(principal), auth, language));
    }

    /**
     * Contract §4.15, 10-StudentIdentityAndOnboarding.md §3.7. <b>An admin
     * reset issues an activation code and returns a slip — it does not
     * generate a password</b>, which is what makes §2.4's "no password is ever
     * generated server-side" true rather than merely intended (issue 088).
     *
     * <p>It lives beside the class batch rather than in {@code UserController}
     * because it now <em>is</em> a slip issuance: same admin-only rule, same
     * language resolution, same PDF response and the same reason for being a
     * POST. Keeping it next to {@code PATCH /users/{id}} would have meant
     * maintaining a second copy of all four.</p>
     *
     * <p>Revocation stays where it was, after the service returns: the account
     * no longer has a password, but a session established before the reset
     * carries its own authenticated principal and would otherwise stay usable.
     * It runs after the transaction commits, so a rolled-back reset cannot sign
     * anybody out.</p>
     */
    @PostMapping(value = "/{id}/reset-password", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('users.manage')")
    public ResponseEntity<byte[]> resetCredential(
            @PathVariable String id,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            Authentication auth) {
        CookieSessionUserDetails principal = (CookieSessionUserDetails) auth.getPrincipal();
        String language = requestLanguageService.resolve(acceptLanguage, principal.preferredLanguage());

        ResetSlip reset = credentialSlipService.issueForReset(id, actorOf(principal), language);
        sessionRevocationService.revokeAllSessionsForUser(reset.username());
        return pdfDownload(reset.document());
    }

    private SlipActor actorOf(CookieSessionUserDetails principal) {
        return new SlipActor(principal.id(), principal.displayName(),
                principal.roleCodes().stream().anyMatch(SLIP_ISSUING_ROLES::contains));
    }

    private ResponseEntity<byte[]> pdfDownload(SlipDocument document) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + document.filename() + "\"")
                // Every page of this body is a working credential. `no-store`
                // keeps it out of the browser's disk cache and out of any
                // intermediary's, which matters more here than it does for
                // evidence: evidence behind a short-lived token expires, a
                // printed activation code does not stop being valid because a
                // cache entry aged.
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(document.pdf());
    }
}
