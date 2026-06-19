package com.bugeon.fintechledger.infrastructure.config;

import com.bugeon.fintechledger.auth.security.JwtAuthenticationEntryPoint;
import com.bugeon.fintechledger.auth.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 configuration.
 *
 * Key decisions:
 *   - Stateless JWT session (no HttpSession, no CSRF needed)
 *   - JwtAuthenticationFilter runs before UsernamePasswordAuthenticationFilter
 *   - JwtAuthenticationEntryPoint returns structured JSON 401
 *   - BCrypt strength 12 for password hashing
 *   - DaoAuthenticationProvider wires UserDetailsService into Spring Security
 *
 * Public endpoints:
 *   POST /api/v1/auth/signup
 *   POST /api/v1/auth/login
 *   POST /api/v1/auth/refresh
 *   /swagger-ui/** and /v3/api-docs/**
 *   GET /actuator/health
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    // ── Path constants ────────────────────────────────────────────────────────

    private static final String SWAGGER_UI      = "/swagger-ui/**";
    private static final String SWAGGER_HTML    = "/swagger-ui.html";
    private static final String API_DOCS        = "/v3/api-docs/**";
    private static final String ACTUATOR_HEALTH = "/actuator/health";
    private static final String AUTH_SIGNUP     = "/api/v1/auth/signup";
    private static final String AUTH_LOGIN      = "/api/v1/auth/login";
    private static final String AUTH_REFRESH    = "/api/v1/auth/refresh";

    // ── Injected beans ────────────────────────────────────────────────────────

    private final JwtAuthenticationFilter     jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final UserDetailsService          userDetailsService;

    // ── Security filter chain ─────────────────────────────────────────────────

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless REST API: disable CSRF and server-side session
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Structured JSON 401 on unauthenticated access
            .exceptionHandling(ex ->
                ex.authenticationEntryPoint(jwtAuthenticationEntryPoint))

            // URL-level authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public auth endpoints (POST only)
                .requestMatchers(HttpMethod.POST, AUTH_SIGNUP, AUTH_LOGIN, AUTH_REFRESH).permitAll()
                // OpenAPI / Swagger UI (GET, no auth required)
                .requestMatchers(SWAGGER_HTML, SWAGGER_UI, API_DOCS).permitAll()
                // Actuator health probe
                .requestMatchers(ACTUATOR_HEALTH).permitAll()
                // Everything else requires a valid JWT access token
                .anyRequest().authenticated()
            )

            // Wire our DAO provider and JWT filter
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthenticationFilter,
                             UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // ── Authentication provider ───────────────────────────────────────────────

    /**
     * Wires UserDetailsService and PasswordEncoder into Spring Security's
     * DAO authentication flow.
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /**
     * Exposes AuthenticationManager as a Bean.
     * Required for programmatic authentication in the login flow.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // ── Password encoder ──────────────────────────────────────────────────────

    /**
     * BCrypt with work factor 12.
     * Approx 250 ms per hash on modern hardware — acceptable for login latency.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
