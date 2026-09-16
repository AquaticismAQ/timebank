package cn.timebank.iam.application;

import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Credential checking is delegated to Spring Security's {@link
 * AuthenticationManager} (backed by {@code security}'s {@code
 * DaoAuthenticationProvider} + {@code UserDetailsServiceImpl}) rather than
 * hand-rolled here — one framework-verified path for password comparison
 * and account-status checks (locked/disabled), instead of a second,
 * divergent implementation. This class adds the business rules the
 * framework does not know about: the lockout-threshold counter and login
 * logging.
 */
@Service
class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final LoginLogService loginLogService;

    AuthServiceImpl(AuthenticationManager authenticationManager, UserService userService,
                     LoginLogService loginLogService) {
        this.authenticationManager = authenticationManager;
        this.userService = userService;
        this.loginLogService = loginLogService;
    }

    /**
     * Deliberately NOT {@code @Transactional}: this method throws on every
     * failure path, and a rollback would take the failed-login counter and
     * login-log write down with it — the opposite of what a lockout
     * mechanism needs. Each collaborator below (recordFailedLogin,
     * loginLogService.record, recordSuccessfulLogin) manages its own
     * transaction boundary and commits independently of what this method
     * does afterward, including throw.
     */
    @Override
    public AuthenticatedUserView authenticate(String username, String password, String ipAddress, String userAgent) {
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        } catch (UsernameNotFoundException | BadCredentialsException e) {
            Optional<AuthenticatedUserView> viewOpt = userService.findByUsernameForAuth(username);
            viewOpt.ifPresent(v -> userService.recordFailedLogin(v.id()));
            loginLogService.record(viewOpt.map(AuthenticatedUserView::id).orElse(null), username, false,
                    viewOpt.isEmpty() ? "UNKNOWN_USERNAME" : "BAD_PASSWORD", ipAddress, userAgent);
            throw ApiException.authenticationRequired("Invalid username or password.");
        } catch (LockedException e) {
            loginLogService.record(null, username, false, "LOCKED", ipAddress, userAgent);
            throw ApiException.authenticationRequired("This account is temporarily locked. Try again later.");
        } catch (DisabledException e) {
            loginLogService.record(null, username, false, "INACTIVE", ipAddress, userAgent);
            throw ApiException.authenticationRequired("This account is inactive.");
        }

        // AuthenticationManager confirmed the credentials; re-fetch rather than
        // downcast its principal, keeping this class free of any `security.*` import.
        AuthenticatedUserView view = userService.findByUsernameForAuth(username)
                .orElseThrow(() -> new IllegalStateException("User vanished between authentication and lookup"));
        userService.recordSuccessfulLogin(view.id());
        loginLogService.record(view.id(), username, true, null, ipAddress, userAgent);
        return view;
    }
}
