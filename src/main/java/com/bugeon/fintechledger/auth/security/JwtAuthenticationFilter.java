package com.bugeon.fintechledger.auth.security;

import com.bugeon.fintechledger.common.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-request JWT authentication filter.
 *
 * <h3>Filter pipeline position</h3>
 * Registered before {@link org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter}.
 *
 * <h3>Processing flow</h3>
 * <ol>
 *   <li>Extract the raw token from {@code Authorization: Bearer <token>}.</li>
 *   <li>Validate signature and expiry via {@link JwtTokenProvider}.</li>
 *   <li>Load full {@link UserDetails} from DB using the {@code email} claim.</li>
 *   <li>Build a {@link UsernamePasswordAuthenticationToken} and store it in
 *       {@link SecurityContextHolder}.</li>
 * </ol>
 *
 * If the token is absent or invalid the request continues unauthenticated;
 * Spring Security's authorization rules will then reject it for protected endpoints.
 *
 * Extends {@link OncePerRequestFilter} — guaranteed to run exactly once per request.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX         = "Bearer ";

    private final JwtTokenProvider         jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {

        String token = extractBearerToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticateFromToken(token, request);
        }

        chain.doFilter(request, response);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void authenticateFromToken(String token, HttpServletRequest request) {
        try {
            jwtTokenProvider.validateToken(token);

            String email = jwtTokenProvider.extractEmail(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            if (userDetails.isEnabled()) {
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("Authenticated [{}] → {}", email, request.getRequestURI());
            }

        } catch (BusinessException ex) {
            // Token is expired or invalid — proceed unauthenticated.
            // Spring Security authorization will reject protected endpoints.
            log.debug("JWT auth failed [{}]: {}", request.getRequestURI(), ex.getMessage());
        }
    }

    /**
     * Extracts the raw token string from the Authorization header.
     *
     * @return the token string, or {@code null} if the header is absent or not Bearer
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
