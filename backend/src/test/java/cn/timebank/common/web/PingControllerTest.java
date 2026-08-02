package cn.timebank.common.web;

import cn.timebank.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the response envelope (B0.3, contract §2.5) and the error
 * envelope (B0.4, contract §2.6) end to end through real HTTP handling —
 * no mocked service layer, since {@link PingController} exists only to
 * make this provable. Spring Security's auto-configuration is excluded
 * rather than importing the real {@code SecurityConfig} — that config's
 * {@code AuthenticationManager} bean needs a real {@code UserDetailsService}
 * backed by the database (B1), which this narrow slice deliberately has
 * none of. {@code RequestIdFilter} is a plain servlet filter, unrelated to
 * Spring Security, so it still registers and runs normally.
 * {@code PingController}'s own paths are {@code permitAll()} in production
 * regardless (see {@code SecurityConfig}).
 */
@WebMvcTest(controllers = PingController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
@Import({GlobalExceptionHandler.class, RequestIdFilter.class})
class PingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void successResponseUsesTheDataMetaEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ok"))
                .andExpect(jsonPath("$.meta.requestId").exists())
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void notFoundUsesTheErrorEnvelopeWithRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/ping/fail"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").exists())
                .andExpect(jsonPath("$.error.requestId").exists());
    }

    @Test
    void forbiddenMapsTo403WithGenericMessage() throws Exception {
        mockMvc.perform(get("/api/v1/ping/fail").param("as", "forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void stateConflictMapsTo409() throws Exception {
        mockMvc.perform(get("/api/v1/ping/fail").param("as", "conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
    }
}
