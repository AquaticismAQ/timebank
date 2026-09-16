package cn.timebank.iam.web;

import cn.timebank.common.error.GlobalExceptionHandler;
import cn.timebank.iam.application.ActivationService;
import cn.timebank.iam.application.AuthService;
import cn.timebank.iam.application.UserService;
import cn.timebank.security.application.SessionRevocationService;
import cn.timebank.security.application.TeacherScopeAssignmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The system has no self-service password recovery
 * (`Design/*&#47;08-mlps-level1-security-design-guide.md` §8.1/§8.2, issue
 * 089). {@code POST /auth/forgot-password} and {@code POST
 * /auth/reset-password} used to exist and are gone.
 *
 * <h2>Why this asserts on routing rather than on the absence of a class</h2>
 *
 * <p>"The handler method was deleted" is a claim about source. What matters to
 * an unauthenticated attacker is whether the <em>path</em> answers, and a path
 * can come back without the original method returning — a second controller, a
 * view-controller registration, a forwarding rule. So this drives real HTTP
 * through the real {@code DispatcherServlet} and asserts the removed paths are
 * unmapped.</p>
 *
 * <p>Spring Security's auto-configuration is excluded, following {@code
 * PingControllerTest}: the real {@code SecurityConfig} needs a database-backed
 * {@code UserDetailsService} this slice has none of. That exclusion makes the
 * assertion <em>stronger</em>, not weaker — with no filter chain in front, a
 * 404 here cannot be a 404 produced by an access rule, and nothing is being
 * mistaken for security that is really just authorization.</p>
 *
 * <p>{@code GlobalExceptionHandler} is deliberately <em>included</em>. It used
 * to be excluded, because its catch-all {@code @ExceptionHandler(Exception.class)}
 * turned an unmapped path's {@code NoResourceFoundException} into a 500 and hid
 * the very status this test is about. Excluding it made the test pass while the
 * deployed application answered 500 — the test asserted a 404 that production
 * did not produce, which was confirmed by probing the live server. The handler
 * now maps that exception to 404 explicitly (issue 091), so this test runs
 * through the same handler the real application uses, and would fail again if
 * that branch were removed.</p>
 *
 * <p>Each case asserts the resolved handler as well as the status. The handler
 * is deliberately <em>not</em> asserted to be null: an unmapped path falls
 * through to the static {@code ResourceHttpRequestHandler}, which then finds no
 * such file and 404s — the same fall-through the deployed app has, since it
 * serves the built frontend from the same context. What must be true is
 * narrower and is what is asserted: <b>no {@code HandlerMethod} matches</b>, so
 * no controller anywhere claims the path. That holds regardless of what the
 * fall-through does with it afterwards.</p>
 *
 * <h2>The controls</h2>
 *
 * <p>Every negative assertion is paired with a surviving sibling path asserted
 * through the same MockMvc and the same slice. Without them a slice that failed
 * to map {@link AuthController} at all — a typo in {@code controllers =}, a
 * broken context — would 404 on everything and report success.
 * {@code /auth/login} and {@code /auth/activate} reach a handler and reject the
 * body (400), which is a mapping existing.</p>
 */
@WebMvcTest(controllers = AuthController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
class AuthControllerRoutingTest {

    /** Both halves of the removed flow: the request for a token, and its redemption. */
    private static final String FORGOT = "/api/v1/auth/forgot-password";
    private static final String RESET = "/api/v1/auth/reset-password";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private ActivationService activationService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private SessionRevocationService sessionRevocationService;
    @MockitoBean
    private TeacherScopeAssignmentService teacherScopeAssignmentService;

    @ParameterizedTest
    @ValueSource(strings = {FORGOT, RESET})
    void the_removed_recovery_paths_are_not_routed(String path) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"31415926\",\"token\":\"t\",\"newPassword\":\"a-new-password\"}"))
                .andReturn();

        assertThat(result.getHandler())
                .as("%s must not resolve to any controller method", path)
                .isNotInstanceOf(HandlerMethod.class);
        // 404, and specifically not 405: a 405 would mean the path is still
        // mapped and only the verb is wrong, which is a mapping that exists.
        assertThat(result.getResponse().getStatus())
                .as("%s must not be routed at all", path)
                .isEqualTo(404);
    }

    /**
     * A removed POST endpoint must not survive as a GET either — the same path
     * answering any verb is the path still being there.
     */
    @ParameterizedTest
    @ValueSource(strings = {FORGOT, RESET})
    void the_removed_recovery_paths_answer_no_other_verb(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertThat(result.getHandler())
                .as("%s must not resolve to any controller method under GET either", path)
                .isNotInstanceOf(HandlerMethod.class);
        assertThat(result.getResponse().getStatus())
                .as("%s must not be reachable by GET either", path)
                .isEqualTo(404);
    }

    /**
     * The control that makes the two tests above mean something: sibling paths
     * on the very same controller, in the very same slice, with the very same
     * unreadable body. These reach a handler and fail on the body (422 —
     * {@code VALIDATION_FAILED} per contract §2.6); the removed ones never
     * reach one (404). The 422 is the application's real answer: before
     * {@code GlobalExceptionHandler} was included here this read 400, which
     * was only ever Spring's un-handled default and not what the deployed
     * server returns.
     */
    @Test
    void the_surviving_auth_paths_are_routed_in_this_same_slice() throws Exception {
        for (String surviving : new String[]{"/api/v1/auth/login", "/api/v1/auth/activate"}) {
            MvcResult result = mockMvc.perform(post(surviving)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn();
            assertThat(result.getHandler())
                    .as("%s still resolves to a controller method, so the assertions above are "
                            + "about the removed paths and not about this slice mapping nothing", surviving)
                    .isInstanceOf(HandlerMethod.class);
            assertThat(result.getResponse().getStatus())
                    .as("%s reaches its handler and rejects the empty body through the real "
                            + "exception handler, so it is not a 404 and not a framework default", surviving)
                    .isEqualTo(422);
        }
    }
}
