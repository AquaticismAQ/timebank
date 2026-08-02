package cn.timebank.iam.application;

import cn.timebank.iam.application.dto.AuthenticatedUserView;

/**
 * Credential verification and the password-recovery token flow. Session
 * creation itself (cookie, `SecurityContext`, timeout) is a web-layer
 * concern handled by the controller — this service only decides whether
 * credentials are valid, recording login logs and lockout state either way.
 */
public interface AuthService {

    /** @throws cn.timebank.common.error.ApiException (401) on any invalid-credential, locked, or inactive case */
    AuthenticatedUserView authenticate(String username, String password, String ipAddress, String userAgent);

    /**
     * Always returns normally, whether or not the username exists — contract
     * §2.4's existence-hiding principle applies here too: a caller must not
     * be able to enumerate valid usernames through this endpoint's response.
     */
    void forgotPassword(String username);

    /** @return the affected user's username, so the caller can revoke live sessions */
    String resetPassword(String token, String newPassword);
}
