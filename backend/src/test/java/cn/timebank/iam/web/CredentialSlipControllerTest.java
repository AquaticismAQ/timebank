package cn.timebank.iam.web;

import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.CredentialSlipService;
import cn.timebank.iam.application.CredentialSlipService.ResetSlip;
import cn.timebank.iam.application.CredentialSlipService.SlipActor;
import cn.timebank.iam.application.CredentialSlipService.SlipDocument;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.organization.application.RequestLanguageService;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.SessionRevocationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The web half of 10-StudentIdentityAndOnboarding.md §3.7.
 *
 * <p>Session revocation is the part that only exists here: {@code iam} cannot
 * call {@code security} (module cycle — see {@code UserServiceImpl}'s Javadoc),
 * so the controller is the only place it can happen, and a reset that leaves a
 * live session authenticated is not a reset. Clearing the password does not
 * end a session on its own: a cookie-backed session carries its own
 * already-authenticated principal and never presents a password again.</p>
 */
class CredentialSlipControllerTest {

    private static final String TARGET_ID = "usr_target";
    private static final String TARGET_USERNAME = "31415926";

    private final CredentialSlipService credentialSlipService = mock(CredentialSlipService.class);
    private final RequestLanguageService requestLanguageService = mock(RequestLanguageService.class);
    private final SessionRevocationService sessionRevocationService = mock(SessionRevocationService.class);

    private final CredentialSlipController controller = new CredentialSlipController(
            credentialSlipService, requestLanguageService, sessionRevocationService);

    @Test
    void a_reset_revokes_every_live_session_of_the_account_it_reset() {
        when(requestLanguageService.resolve(anyString(), anyString())).thenReturn("en");
        when(credentialSlipService.issueForReset(eq(TARGET_ID), any(SlipActor.class), eq("en")))
                .thenReturn(new ResetSlip(
                        new SlipDocument("%PDF-1.4".getBytes(), "activation-slip-20260917.pdf", 1),
                        TARGET_USERNAME));

        ResponseEntity<byte[]> response = controller.resetCredential(TARGET_ID, "en", adminAuth());

        verify(sessionRevocationService).revokeAllSessionsForUser(TARGET_USERNAME);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("activation-slip-20260917.pdf");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store, private, max-age=0");
    }

    /**
     * The revocation must be keyed to the account being reset, not to whoever
     * asked. Signing the admin out of their own session instead would look
     * like "a reset happened" while leaving the reset account's session alive
     * — the exact failure this endpoint exists to prevent.
     */
    @Test
    void the_admins_own_session_is_not_the_one_revoked() {
        when(requestLanguageService.resolve(anyString(), anyString())).thenReturn("en");
        when(credentialSlipService.issueForReset(anyString(), any(SlipActor.class), anyString()))
                .thenReturn(new ResetSlip(new SlipDocument(new byte[]{1}, "activation-slip.pdf", 1),
                        TARGET_USERNAME));

        controller.resetCredential(TARGET_ID, "en", adminAuth());

        ArgumentCaptor<String> revoked = ArgumentCaptor.forClass(String.class);
        verify(sessionRevocationService).revokeAllSessionsForUser(revoked.capture());
        assertThat(revoked.getValue()).isEqualTo(TARGET_USERNAME).isNotEqualTo("admin001");
    }

    /** A refused or failed reset changed nothing, so it must not sign anybody out either. */
    @Test
    void a_failed_reset_revokes_nothing() {
        when(requestLanguageService.resolve(anyString(), anyString())).thenReturn("en");
        when(credentialSlipService.issueForReset(anyString(), any(SlipActor.class), anyString()))
                .thenThrow(ApiException.stateConflict("nope"));

        assertThatThrownBy(() -> controller.resetCredential(TARGET_ID, "en", adminAuth()))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(sessionRevocationService);
    }

    /**
     * §2.10, D-9/D-10 is enforced in the service, but the controller is what
     * tells it who is asking — a principal holding {@code users.manage} through
     * some other role must arrive with {@code isAdmin} false rather than having
     * the question skipped.
     */
    @Test
    void a_non_admin_role_reaches_the_service_marked_as_not_admin() {
        when(requestLanguageService.resolve(anyString(), anyString())).thenReturn("en");
        when(credentialSlipService.issueForReset(anyString(), any(SlipActor.class), anyString()))
                .thenThrow(ApiException.forbidden("nope"));

        assertThatThrownBy(() -> controller.resetCredential(TARGET_ID, "en", authFor("teacher")))
                .isInstanceOf(ApiException.class);

        ArgumentCaptor<SlipActor> actor = ArgumentCaptor.forClass(SlipActor.class);
        verify(credentialSlipService).issueForReset(anyString(), actor.capture(), anyString());
        assertThat(actor.getValue().isAdmin()).isFalse();
        verify(sessionRevocationService, never()).revokeAllSessionsForUser(anyString());
    }

    @Test
    void an_admin_role_reaches_the_service_marked_as_admin() {
        when(requestLanguageService.resolve(anyString(), anyString())).thenReturn("en");
        when(credentialSlipService.issueForReset(anyString(), any(SlipActor.class), anyString()))
                .thenReturn(new ResetSlip(new SlipDocument(new byte[]{1}, "activation-slip.pdf", 1),
                        TARGET_USERNAME));

        controller.resetCredential(TARGET_ID, "en", adminAuth());

        ArgumentCaptor<SlipActor> actor = ArgumentCaptor.forClass(SlipActor.class);
        verify(credentialSlipService).issueForReset(anyString(), actor.capture(), anyString());
        assertThat(actor.getValue().isAdmin()).isTrue();
        assertThat(actor.getValue().userId()).isEqualTo("usr_admin");
    }

    private static Authentication adminAuth() {
        return authFor("admin");
    }

    private static Authentication authFor(String roleCode) {
        AuthenticatedUserView view = new AuthenticatedUserView("usr_admin", "admin001", "irrelevant",
                "Example Admin", true, false, "zh-CN", Set.of(roleCode), Set.of("users.manage"), null, 480);
        CookieSessionUserDetails principal = new CookieSessionUserDetails(view);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
