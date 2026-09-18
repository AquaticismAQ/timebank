package cn.timebank.security;

import cn.timebank.security.web.SecurityErrorHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * B1's real security configuration, expanding B0's permit-all placeholder
 * in place (the class was always meant to grow here, not be replaced).
 *
 * Three checks, applied in order, per Design.md §10: authentication (this
 * filter chain), permission code ({@code @PreAuthorize} on every endpoint,
 * B1.6), data scope (B1.7, applied inside each module's queries — this
 * class has no opinion on it).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                          PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider::authenticate;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SecurityErrorHandlers securityErrorHandlers) throws Exception {
        http
                // Double-submit cookie CSRF (contract §2.3): the token cookie is
                // JS-readable so the frontend can echo it back in a header; the
                // session cookie itself stays HttpOnly regardless (server.servlet.session.cookie.*).
                // The plain (non-XOR) request handler is required here: Spring
                // Security 6.2+ defaults to BREACH-protection XOR-masking, whose
                // per-request token (what a controller's CsrfToken.getToken()
                // returns) deliberately differs from the raw cookie value — fine
                // for server-rendered forms, but it breaks a double-submit-cookie
                // pattern where the client is expected to echo the cookie's own
                // value back verbatim in a header.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/ping/**").permitAll()
                        // The school's name only, for the login page — see
                        // SchoolInfoController.getIdentity(). Deliberately not
                        // /school/info, which also carries a staff contact.
                        .requestMatchers(HttpMethod.GET, "/api/v1/school/identity").permitAll()
                        .requestMatchers("/actuator/**").permitAll() // already loopback-only (B0.11)
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityErrorHandlers)
                        .accessDeniedHandler(securityErrorHandlers))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable()); // contract's /auth/logout is our own controller, not Spring's default
        return http.build();
    }
}
