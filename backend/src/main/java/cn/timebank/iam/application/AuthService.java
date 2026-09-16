package cn.timebank.iam.application;

import cn.timebank.iam.application.dto.AuthenticatedUserView;

/**
 * Credential verification. Session creation itself (cookie,
 * `SecurityContext`, timeout) is a web-layer concern handled by the
 * controller — this service only decides whether credentials are valid,
 * recording login logs and lockout state either way.
 *
 * <p>There is no password-recovery method here, by design
 * (`Design/*&#47;08-mlps-level1-security-design-guide.md` §8.1/§8.2, issue
 * 089). Recovery is admin-initiated and offline-verified: {@code
 * CredentialSlipService.issueForReset} clears the credential and issues an
 * activation slip. Nothing unauthenticated may mint a credential for a named
 * account.</p>
 */
public interface AuthService {

    /** @throws cn.timebank.common.error.ApiException (401) on any invalid-credential, locked, or inactive case */
    AuthenticatedUserView authenticate(String username, String password, String ipAddress, String userAgent);
}
