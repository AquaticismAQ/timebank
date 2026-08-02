package cn.timebank.iam.application;

import cn.timebank.common.OpaqueIds;
import cn.timebank.common.error.ApiException;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.domain.PasswordResetToken;
import cn.timebank.iam.infrastructure.PasswordResetTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
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

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final int RESET_TOKEN_TTL_MINUTES = 30;

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final LoginLogService loginLogService;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    AuthServiceImpl(AuthenticationManager authenticationManager, UserService userService,
                     LoginLogService loginLogService, PasswordResetTokenRepository resetTokenRepository) {
        this.authenticationManager = authenticationManager;
        this.userService = userService;
        this.loginLogService = loginLogService;
        this.resetTokenRepository = resetTokenRepository;
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

    @Override
    @Transactional
    public void forgotPassword(String username) {
        Optional<AuthenticatedUserView> viewOpt = userService.findByUsernameForAuth(username);
        if (viewOpt.isEmpty()) {
            // Deliberately silent — do not let this endpoint reveal which usernames exist.
            return;
        }
        String rawToken = generateRawToken();
        String tokenHash = sha256Hex(rawToken);
        Instant now = Instant.now();
        resetTokenRepository.save(new PasswordResetToken(OpaqueIds.generate("prt"), viewOpt.get().id(),
                tokenHash, now.plus(RESET_TOKEN_TTL_MINUTES, ChronoUnit.MINUTES), now));

        // No notification module exists until B5 — the token is logged, not
        // emailed. This is a real, tracked gap (Docs/implementation/00-Status.md),
        // not a silent shortcut: an operator must currently relay it out of band.
        log.warn("Password reset token generated for username='{}' (no mail infra yet — relay manually): {}",
                username, rawToken);
    }

    @Override
    @Transactional
    public String resetPassword(String token, String newPassword) {
        String tokenHash = sha256Hex(token);
        PasswordResetToken resetToken = resetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> ApiException.invalidRequest("This reset link is invalid or has expired."));
        if (!resetToken.isUsable(Instant.now())) {
            throw ApiException.invalidRequest("This reset link is invalid or has expired.");
        }
        String username = userService.resetPasswordWithToken(resetToken.getUserId(), newPassword);
        resetToken.setUsedAt(Instant.now());
        return username;
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash reset token", e);
        }
    }
}
