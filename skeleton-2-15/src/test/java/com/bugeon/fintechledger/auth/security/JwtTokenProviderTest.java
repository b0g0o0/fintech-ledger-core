package com.bugeon.fintechledger.auth.security;

import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.infrastructure.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private static final String SECRET =
            "test-secret-that-is-definitely-long-enough-for-hs256-algorithm";
    private static final long   ACCESS_EXPIRY_MS  = 900_000L;
    private static final long   REFRESH_EXPIRY_MS = 604_800_000L;

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessExpiryMs(ACCESS_EXPIRY_MS);
        props.setRefreshExpiryMs(REFRESH_EXPIRY_MS);
        provider = new JwtTokenProvider(props);
    }

    // ── generateAccessToken ───────────────────────────────────────────────────

    @Nested
    @DisplayName("generateAccessToken")
    class GenerateAccessToken {

        @Test
        @DisplayName("returns a non-blank compact JWT")
        void returnsNonBlankToken() {
            String token = generate();
            assertThat(token)
                    .isNotBlank()
                    .contains(".")     // header.payload.signature
                    .matches("[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
        }

        @Test
        @DisplayName("sub claim equals the userId string")
        void subClaimEqualsUserId() {
            UUID   userId = UUID.randomUUID();
            String token  = provider.generateAccessToken(userId, "a@b.com",
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));

            assertThat(provider.extractUserId(token)).isEqualTo(userId);
        }

        @Test
        @DisplayName("email claim matches the provided email")
        void emailClaimMatches() {
            String email = "test@example.com";
            String token = provider.generateAccessToken(UUID.randomUUID(), email,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));

            assertThat(provider.extractEmail(token)).isEqualTo(email);
        }

        @Test
        @DisplayName("two tokens generated at different times are distinct")
        void tokensAreUnique() throws InterruptedException {
            String t1 = generate();
            Thread.sleep(10);
            String t2 = generate();
            assertThat(t1).isNotEqualTo(t2);
        }
    }

    // ── validateToken ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("validateToken")
    class ValidateToken {

        @Test
        @DisplayName("valid token does not throw")
        void validTokenPasses() {
            assertThatNoException().isThrownBy(() -> provider.validateToken(generate()));
        }

        @Test
    @DisplayName("tampered signature throws TOKEN_INVALID")
    void tamperedSignatureThrows() {
        String token = generate();

        String[] parts = token.split("\\.");
        parts[2] = "INVALID_SIGNATURE";

        String tampered = String.join(".", parts);

        assertThatThrownBy(() -> provider.validateToken(tampered))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOKEN_INVALID);
    }

        @Test
        @DisplayName("completely invalid string throws TOKEN_INVALID")
        void garbledStringThrows() {
            assertThatThrownBy(() -> provider.validateToken("not.a.token"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TOKEN_INVALID);
        }

        @Test
        @DisplayName("blank token throws TOKEN_INVALID")
        void blankTokenThrows() {
            assertThatThrownBy(() -> provider.validateToken(""))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TOKEN_INVALID);
        }

        @Test
        @DisplayName("expired token throws TOKEN_EXPIRED")
        void expiredTokenThrows() {
            JwtProperties expiredProps = new JwtProperties();
            expiredProps.setSecret(SECRET);
            expiredProps.setAccessExpiryMs(-1_000L);   // already expired
            expiredProps.setRefreshExpiryMs(REFRESH_EXPIRY_MS);
            JwtTokenProvider expiredProvider = new JwtTokenProvider(expiredProps);

            String expiredToken = expiredProvider.generateAccessToken(
                    UUID.randomUUID(), "x@y.com",
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));

            assertThatThrownBy(() -> provider.validateToken(expiredToken))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TOKEN_EXPIRED);
        }
    }

    // ── getAccessExpiryMs ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getAccessExpiryMs returns configured value")
    void getAccessExpiryMsReturnsConfiguredValue() {
        assertThat(provider.getAccessExpiryMs()).isEqualTo(ACCESS_EXPIRY_MS);
    }

    // ── secret too short ──────────────────────────────────────────────────────

    @Test
    @DisplayName("constructor throws IllegalStateException when secret is too short")
    void shortSecretThrows() {
        JwtProperties props = new JwtProperties();
        props.setSecret("short");
        props.setAccessExpiryMs(ACCESS_EXPIRY_MS);
        props.setRefreshExpiryMs(REFRESH_EXPIRY_MS);

        assertThatThrownBy(() -> new JwtTokenProvider(props))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String generate() {
        return provider.generateAccessToken(
                UUID.randomUUID(), "user@example.com",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
