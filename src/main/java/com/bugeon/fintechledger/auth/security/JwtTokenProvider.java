package com.bugeon.fintechledger.auth.security;

import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.infrastructure.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles all JWT operations for the fintech-ledger service.
 *
 * <h3>Token structure</h3>
 * <pre>
 * Header  : { alg: "HS256", typ: "JWT" }
 * Payload : {
 *   sub   : "&lt;userId UUID&gt;",
 *   email : "user@example.com",
 *   roles : ["ROLE_USER"],          // ← required per spec
 *   iat   : 1715000000,
 *   exp   : 1715000900
 * }
 * </pre>
 *
 * <h3>Key security notes</h3>
 * <ul>
 *   <li>Secret must be ≥ 256 bits (32 UTF-8 bytes) for HS256.</li>
 *   <li>The signing key is derived once at construction time.</li>
 *   <li>A single shared {@link JwtParser} is used for all validations (thread-safe).</li>
 *   <li>Expired tokens produce {@link ErrorCode#TOKEN_EXPIRED} (HTTP 401).</li>
 *   <li>Tampered tokens produce {@link ErrorCode#TOKEN_INVALID} (HTTP 401).</li>
 * </ul>
 */
@Component
@Slf4j
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";

    private final SecretKey signingKey;
    private final long      accessExpiryMs;
    private final JwtParser parser;

    public JwtTokenProvider(JwtProperties props) {
        byte[] keyBytes = props.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be at least 256 bits (32 characters). "
                    + "Generate with: openssl rand -hex 64");
        }
        this.signingKey     = Keys.hmacShaKeyFor(keyBytes);
        this.accessExpiryMs = props.getAccessExpiryMs();
        this.parser         = Jwts.parser()
                                  .verifyWith(signingKey)
                                  .build();
    }

    // ── Token generation ──────────────────────────────────────────────────────

    /**
     * Generates a signed JWT access token.
     *
     * @param userId      stored as the {@code sub} claim
     * @param email       stored as the {@code email} custom claim
     * @param authorities the user's granted authorities — stored as {@code roles} claim
     * @return compact, URL-safe JWT string
     */
    public String generateAccessToken(UUID userId, String email,
                                      Collection<? extends GrantedAuthority> authorities) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + accessExpiryMs);

        List<String> roles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /**
     * Validates signature and expiry of a raw JWT string.
     *
     * @param token raw JWT (without the {@code Bearer } prefix)
     * @throws BusinessException {@link ErrorCode#TOKEN_EXPIRED}  — token past its expiry
     * @throws BusinessException {@link ErrorCode#TOKEN_INVALID}  — bad signature / malformed
     */
    public void validateToken(String token) {
        try {
            parser.parseSignedClaims(token);
        } catch (ExpiredJwtException ex) {
            log.debug("JWT expired: {}", ex.getMessage());
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            // JwtException is the base class covering all JJWT parse failures:
            //   MalformedJwtException   - bad structure
            //   UnsupportedJwtException - wrong JWT type
            //   SignatureException       - HMAC mismatch (extends SecurityException extends JwtException)
            //   SecurityException        - key type or signature byte-length mismatch
            //   JwtException (base)      - any other parsing failure
            // IllegalArgumentException   - null or blank token string
            log.debug("JWT invalid [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new BusinessException(ErrorCode.TOKEN_INVALID);
        }
    }

    // ── Claims extraction ─────────────────────────────────────────────────────

    /**
     * Extracts the {@code sub} claim as a {@link UUID}.
     * Only call after a successful {@link #validateToken}.
     */
    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    /**
     * Extracts the {@code email} claim.
     * Only call after a successful {@link #validateToken}.
     */
    public String extractEmail(String token) {
        return parseClaims(token).get(CLAIM_EMAIL, String.class);
    }

    /**
     * Returns the access token TTL in milliseconds.
     * Used by the auth service to populate {@code expiresIn} in the response.
     */
    public long getAccessExpiryMs() {
        return accessExpiryMs;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }
}
