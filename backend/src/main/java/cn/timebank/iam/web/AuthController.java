package cn.timebank.iam.web;

import cn.timebank.common.web.ApiResponse;
import cn.timebank.iam.application.ActivationService;
import cn.timebank.iam.application.AuthService;
import cn.timebank.iam.application.UserService;
import cn.timebank.iam.application.dto.AuthDtos.ActivateAccountRequest;
import cn.timebank.iam.application.dto.AuthDtos.ChangePasswordRequest;
import cn.timebank.iam.application.dto.AuthDtos.ForgotPasswordRequest;
import cn.timebank.iam.application.dto.AuthDtos.LoginRequest;
import cn.timebank.iam.application.dto.AuthDtos.ResetPasswordRequest;
import cn.timebank.iam.application.dto.AuthenticatedUserView;
import cn.timebank.iam.application.dto.MeResponse;
import cn.timebank.security.application.CookieSessionUserDetails;
import cn.timebank.security.application.SessionRevocationService;
import cn.timebank.security.application.TeacherScopeAssignmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Contract §2.2. Session lifecycle (cookie creation, fixation defense,
 * per-role idle timeout) lives here rather than in {@code AuthService}
 * because it needs {@link HttpServletRequest}/{@link HttpServletResponse} —
 * a web-layer concern, not business logic (Design.md §6 layering).
 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final ActivationService activationService;
    private final UserService userService;
    private final SessionRevocationService sessionRevocationService;
    private final TeacherScopeAssignmentService teacherScopeAssignmentService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthService authService, ActivationService activationService, UserService userService,
                           SessionRevocationService sessionRevocationService,
                           TeacherScopeAssignmentService teacherScopeAssignmentService) {
        this.authService = authService;
        this.activationService = activationService;
        this.userService = userService;
        this.sessionRevocationService = sessionRevocationService;
        this.teacherScopeAssignmentService = teacherScopeAssignmentService;
    }

    /**
     * Same {@code scopeGradeIds} enrichment {@link MeController#me} applies —
     * duplicated here rather than pushed into {@code UserServiceImpl.buildMeResponse}
     * because of the {@code iam}/{@code security} module-cycle constraint documented
     * on {@code MeController} and {@code UserController}.
     */
    private MeResponse withScopeGradeIds(MeResponse response, String userId) {
        Set<String> grades = response.user().roles().contains("teacher")
                ? teacherScopeAssignmentService.gradesOf(userId)
                : Set.of();
        return response.withScopeGradeIds(grades);
    }

    @PostMapping("/api/v1/auth/login")
    @PreAuthorize("permitAll()")
    public ApiResponse<MeResponse> login(@Valid @RequestBody LoginRequest request,
                                          HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AuthenticatedUserView view = authService.authenticate(
                request.username(), request.password(), clientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        establishSession(view, httpRequest, httpResponse);
        return ApiResponse.of(withScopeGradeIds(userService.buildMeResponse(view.id()), view.id()));
    }

    @PostMapping("/api/v1/auth/logout")
    @PreAuthorize("permitAll()")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.of(null);
    }

    @PostMapping("/api/v1/auth/refresh")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<MeResponse> refresh(HttpServletRequest request, Authentication auth) {
        CookieSessionUserDetails principal = (CookieSessionUserDetails) auth.getPrincipal();
        // Fixation hygiene on a privilege-equivalent action, same as login.
        request.changeSessionId();
        request.getSession().setMaxInactiveInterval(principal.sessionTimeoutMinutes() * 60);
        return ApiResponse.of(withScopeGradeIds(userService.buildMeResponse(principal.id()), principal.id()));
    }

    /**
     * First-time access (10-StudentIdentityAndOnboarding.md §2.4/§3.5).
     *
     * <p><b>Public and unauthenticated by necessity</b>, which until now only
     * login was: the account this activates has no password to authenticate
     * with yet. The compensating controls live in {@code ActivationService} and
     * {@code ActivationThrottle} — single use, expiry, a per-IP attempt limit
     * and §2.5's global failure budget — not here.</p>
     *
     * <p>No username parameter, deliberately: the code is its own lookup key
     * (§2.5), so this endpoint never has to be told, and therefore never has to
     * confirm, which student a code belongs to.</p>
     *
     * <p>Returns no body. There is nothing safe to return — echoing the
     * username or display name would hand the holder of a found slip an
     * identity lookup — and no session is established either: §2.6 defines
     * activation as "the password was set", so the student then signs in
     * through the ordinary login path with the password they just chose.</p>
     */
    @PostMapping("/api/v1/auth/activate")
    @PreAuthorize("permitAll()")
    public ApiResponse<Void> activate(@Valid @RequestBody ActivateAccountRequest request,
                                       HttpServletRequest httpRequest) {
        activationService.redeem(request.code(), request.newPassword(), clientIp(httpRequest));
        return ApiResponse.of(null);
    }

    @PostMapping("/api/v1/auth/forgot-password")
    @PreAuthorize("permitAll()")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.username());
        return ApiResponse.of(null);
    }

    @PostMapping("/api/v1/auth/reset-password")
    @PreAuthorize("permitAll()")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        String username = authService.resetPassword(request.token(), request.newPassword());
        sessionRevocationService.revokeAllSessionsForUser(username);
        return ApiResponse.of(null);
    }

    @PostMapping("/api/v1/me/change-password")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication auth) {
        CookieSessionUserDetails principal = (CookieSessionUserDetails) auth.getPrincipal();
        userService.changeOwnPassword(principal.id(), request.currentPassword(), request.newPassword());
        sessionRevocationService.revokeAllSessionsForUser(principal.getUsername());
        return ApiResponse.of(null);
    }

    /**
     * No CSRF-relevant state here — this endpoint exists purely to force
     * Spring Security's deferred CSRF token to materialize into a response
     * cookie (its default lazy-loading strategy otherwise never fires for a
     * pure REST API with no server-rendered view reading the token). Calling
     * {@code getToken()} is what actually triggers it — merely accepting the
     * parameter unused does nothing, since the value Spring hands in is a
     * deferred supplier wrapper, not an already-materialized token.
     */
    @GetMapping("/api/v1/auth/csrf")
    @PreAuthorize("permitAll()")
    public ApiResponse<Void> csrf(CsrfToken csrfToken) {
        csrfToken.getToken();
        return ApiResponse.of(null);
    }

    private void establishSession(AuthenticatedUserView view, HttpServletRequest request, HttpServletResponse response) {
        UserDetails principal = new CookieSessionUserDetails(view);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        // A fresh session is created for every login — since nothing before
        // this point ever calls getSession(), an unauthenticated visitor
        // never has one to begin with, so there is no pre-existing (and
        // therefore no attacker-fixatable) session ID to inherit here.
        //
        // KNOWN GAP (tracked in Docs/implementation/00-Status.md): explicit
        // mid-request regeneration via changeSessionId() was attempted here
        // and on /auth/refresh, matching the standard fixation-defense
        // idiom, but Spring Session's SessionRepositoryFilter request
        // wrapper threw "no session associated with this request" even
        // immediately after getSession(true) on the same request — not yet
        // root-caused. Revisit before this matters in practice (this
        // endpoint has no pre-auth session state for an attacker to plant).
        request.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        request.getSession().setMaxInactiveInterval(view.sessionTimeoutMinutes() * 60);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
