package com.bugeon.fintechledger.auth.service;

import com.bugeon.fintechledger.auth.domain.User;
import com.bugeon.fintechledger.auth.dto.*;
import com.bugeon.fintechledger.auth.repository.UserRepository;
import com.bugeon.fintechledger.auth.security.JwtTokenProvider;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@DisplayName("AuthService")
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository      userRepository;
    @Mock PasswordEncoder     passwordEncoder;
    @Mock JwtTokenProvider    jwtTokenProvider;
    @Mock RefreshTokenService refreshTokenService;

    @InjectMocks AuthService authService;

    // ── signup ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("signup")
    class Signup {

        @Test
        @DisplayName("persists user and returns profile on success")
        void successPath() {
            given(userRepository.existsByEmail("user@example.com")).willReturn(false);
            given(passwordEncoder.encode("pass1234")).willReturn("$bcrypt$hash");
            given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

            SignupResponse res = authService.signup(
                    new SignupRequest("user@example.com", "pass1234", "Alice"));

            assertThat(res.email()).isEqualTo("user@example.com");
            assertThat(res.fullName()).isEqualTo("Alice");
            then(userRepository).should().save(any(User.class));
        }

        @Test
        @DisplayName("normalises email to lowercase")
        void normalisesEmail() {
            given(userRepository.existsByEmail("user@example.com")).willReturn(false);
            given(passwordEncoder.encode(any())).willReturn("hash");
            given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

            SignupResponse res = authService.signup(
                    new SignupRequest("USER@EXAMPLE.COM", "pass1234", "Alice"));

            assertThat(res.email()).isEqualTo("user@example.com");
        }

        @Test
        @DisplayName("throws DUPLICATE_EMAIL when email already registered")
        void throwsOnDuplicateEmail() {
            given(userRepository.existsByEmail("dup@example.com")).willReturn(true);

            assertThatThrownBy(() -> authService.signup(
                    new SignupRequest("dup@example.com", "pass1234", "Alice")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_EMAIL);

            then(userRepository).should(never()).save(any());
        }
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("returns token pair on valid credentials")
        void successPath() {
            User user = User.create("user@example.com", "$bcrypt$hash", "Alice");
            given(userRepository.findByEmail("user@example.com")).willReturn(Optional.of(user));
            given(passwordEncoder.matches("pass1234", "$bcrypt$hash")).willReturn(true);
            given(jwtTokenProvider.generateAccessToken(any(), anyString(),
                    any(Collection.class))).willReturn("access-token");
            given(jwtTokenProvider.getAccessExpiryMs()).willReturn(900_000L);
            given(refreshTokenService.createRefreshToken(any())).willReturn("raw-refresh");

            TokenResponse res = authService.login(
                    new LoginRequest("user@example.com", "pass1234"));

            assertThat(res.accessToken()).isEqualTo("access-token");
            assertThat(res.refreshToken()).isEqualTo("raw-refresh");
            assertThat(res.tokenType()).isEqualTo("Bearer");
            assertThat(res.expiresIn()).isEqualTo(900L);
        }

        @Test
        @DisplayName("throws INVALID_CREDENTIALS when email not found")
        void throwsWhenEmailNotFound() {
            given(userRepository.findByEmail(anyString())).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(
                    new LoginRequest("nobody@example.com", "pass1234")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("throws INVALID_CREDENTIALS when password is wrong")
        void throwsOnWrongPassword() {
            User user = User.create("user@example.com", "$bcrypt$hash", "Alice");
            given(userRepository.findByEmail(anyString())).willReturn(Optional.of(user));
            given(passwordEncoder.matches("wrong", "$bcrypt$hash")).willReturn(false);

            assertThatThrownBy(() -> authService.login(
                    new LoginRequest("user@example.com", "wrong")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("revokes old refresh tokens before issuing new ones")
        void revokesOldTokensOnLogin() {
            User user = User.create("user@example.com", "$bcrypt$hash", "Alice");
            given(userRepository.findByEmail(anyString())).willReturn(Optional.of(user));
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
            given(jwtTokenProvider.generateAccessToken(any(), anyString(),
                    any(Collection.class))).willReturn("token");
            given(jwtTokenProvider.getAccessExpiryMs()).willReturn(900_000L);
            given(refreshTokenService.createRefreshToken(any())).willReturn("refresh");

            authService.login(new LoginRequest("user@example.com", "pass1234"));

            then(refreshTokenService).should().revokeAllForUser(user.getId());
        }
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("refresh")
    class Refresh {

        @Test
        @DisplayName("rotates token and returns new pair")
        void successPath() {
            UUID userId = UUID.randomUUID();
            User user   = User.create("user@example.com", "hash", "Alice");

            given(refreshTokenService.validateAndRotate("old-token")).willReturn(userId);
            given(userRepository.findById(userId)).willReturn(Optional.of(user));
            given(jwtTokenProvider.generateAccessToken(any(), anyString(),
                    any(Collection.class))).willReturn("new-access");
            given(jwtTokenProvider.getAccessExpiryMs()).willReturn(900_000L);
            given(refreshTokenService.createRefreshToken(any())).willReturn("new-refresh");

            TokenResponse res = authService.refresh(new RefreshTokenRequest("old-token"));

            assertThat(res.accessToken()).isEqualTo("new-access");
            assertThat(res.refreshToken()).isEqualTo("new-refresh");
        }

        @Test
        @DisplayName("throws USER_NOT_FOUND when user has been deleted after token issuance")
        void throwsWhenUserDeleted() {
            UUID userId = UUID.randomUUID();
            given(refreshTokenService.validateAndRotate(anyString())).willReturn(userId);
            given(userRepository.findById(userId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("token")))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("delegates revocation to RefreshTokenService")
        void revokesTokens() {
            User          user      = User.create("user@example.com", "hash", "Alice");
            UserPrincipal principal = UserPrincipal.from(user);

            authService.logout(principal);

            then(refreshTokenService).should().revokeAllForUser(principal.getUserId());
        }
    }

    // ── me ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("me")
    class Me {

        @Test
        @DisplayName("returns current user profile from DB")
        void returnsProfile() {
            User          user      = User.create("user@example.com", "hash", "Alice");
            UserPrincipal principal = UserPrincipal.from(user);

            given(userRepository.findById(principal.getUserId()))
                    .willReturn(Optional.of(user));

            UserResponse res = authService.me(principal);

            assertThat(res.email()).isEqualTo("user@example.com");
            assertThat(res.fullName()).isEqualTo("Alice");
        }

        @Test
        @DisplayName("throws USER_NOT_FOUND when user has been soft-deleted")
        void throwsWhenUserDeleted() {
            User          user      = User.create("user@example.com", "hash", "Alice");
            user.softDelete();
            UserPrincipal principal = UserPrincipal.from(user);

            given(userRepository.findById(principal.getUserId()))
                    .willReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.me(principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }
}
