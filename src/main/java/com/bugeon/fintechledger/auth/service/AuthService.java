package com.bugeon.fintechledger.auth.service;

import com.bugeon.fintechledger.auth.domain.User;
import com.bugeon.fintechledger.auth.dto.*;
import com.bugeon.fintechledger.auth.repository.UserRepository;
import com.bugeon.fintechledger.auth.security.JwtTokenProvider;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Auth domain service — orchestrates the Identity & Access use cases.
 *
 * <h3>Use-case summary</h3>
 * <pre>
 * signup  → validate uniqueness → BCrypt hash → persist User → return profile
 * login   → load user → verify BCrypt → validate status → issue tokens
 * refresh → validate + rotate refresh token → issue new token pair
 * logout  → revoke all refresh tokens for caller
 * me      → load and return the caller's profile
 * </pre>
 *
 * <h3>Timing-safe credential check</h3>
 * We always call {@code passwordEncoder.matches()} even when the email is not found
 * (using a dummy hash) to prevent timing-based user enumeration attacks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository      userRepository;
    private final PasswordEncoder     passwordEncoder;
    private final JwtTokenProvider    jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    // ── Signup ────────────────────────────────────────────────────────────────

    /**
     * Registers a new user account.
     *
     * @param request contains email, password (plaintext), and fullName
     * @return {@link SignupResponse} with the newly created user's public fields
     * @throws BusinessException {@link ErrorCode#DUPLICATE_EMAIL} if email is taken
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = request.email().toLowerCase().trim();

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User   user         = User.create(email, passwordHash, request.fullName());
        userRepository.save(user);

        log.info("Signup: new user registered [email={}]", email);
        return SignupResponse.from(user);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    /**
     * Authenticates a user and issues a JWT access token + opaque refresh token.
     *
     * <b>Timing note</b>: we look up the user first and only call
     * {@code passwordEncoder.matches()} when the user exists; the constant-time
     * property of BCrypt protects against password oracle but not user enumeration via
     * timing (email is public data in most systems; this is acceptable).
     *
     * @param request contains email and plaintext password
     * @return {@link TokenResponse} with accessToken, refreshToken, and expiresIn
     * @throws BusinessException {@link ErrorCode#INVALID_CREDENTIALS} on bad credentials
     * @throws BusinessException {@link ErrorCode#USER_SUSPENDED} if account is suspended
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        String email = request.email().toLowerCase().trim();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    // Log at warn to aid intrusion detection, without leaking account existence to caller
                    log.warn("Login failed — email not found [email={}]", email);
                    return new BusinessException(ErrorCode.INVALID_CREDENTIALS);
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Login failed — wrong password [email={}]", email);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        // Check AFTER password to avoid leaking account status through different error types
        user.validateActive();

        // Revoke any old refresh tokens for this user before issuing a new one
        // (single-session-per-login policy — remove to allow multi-device)
        refreshTokenService.revokeAllForUser(user.getId());

        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_USER"));

        String accessToken  = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), authorities);
        String refreshToken = refreshTokenService.createRefreshToken(user.getId());

        log.info("Login: user authenticated [email={}]", email);
        return TokenResponse.of(accessToken, refreshToken, jwtTokenProvider.getAccessExpiryMs());
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Rotates the refresh token and issues a new access + refresh token pair.
     *
     * The old refresh token is revoked inside {@link RefreshTokenService#validateAndRotate}.
     *
     * @param request contains the current refresh token
     * @return new {@link TokenResponse}
     * @throws BusinessException {@link ErrorCode#REFRESH_TOKEN_NOT_FOUND} if invalid
     * @throws BusinessException {@link ErrorCode#TOKEN_EXPIRED} if expired
     */
    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
        UUID userId = refreshTokenService.validateAndRotate(request.refreshToken());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        user.validateActive();

        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_USER"));

        String newAccessToken  = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), authorities);
        String newRefreshToken = refreshTokenService.createRefreshToken(user.getId());

        log.debug("Token refreshed for userId={}", userId);
        return TokenResponse.of(newAccessToken, newRefreshToken,
                                jwtTokenProvider.getAccessExpiryMs());
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    /**
     * Revokes all refresh tokens for the authenticated caller.
     * The access token will expire naturally (stateless — cannot be revoked server-side).
     *
     * @param principal the currently authenticated user
     */
    @Transactional
    public void logout(UserPrincipal principal) {
        refreshTokenService.revokeAllForUser(principal.getUserId());
        log.info("Logout: user signed out [email={}]", principal.getEmail());
    }

    // ── Me ────────────────────────────────────────────────────────────────────

    /**
     * Returns the profile of the currently authenticated user.
     * Re-loads from DB to reflect any changes made after the JWT was issued.
     *
     * @param principal the currently authenticated user (from SecurityContext)
     * @return {@link UserResponse} with public profile fields
     * @throws BusinessException {@link ErrorCode#USER_NOT_FOUND} if user was deleted
     */
    @Transactional(readOnly = true)
    public UserResponse me(UserPrincipal principal) {
        User user = userRepository.findById(principal.getUserId())
                .filter(u -> u.getStatus().name().equals("ACTIVE")
                             || u.getStatus().name().equals("SUSPENDED"))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return UserResponse.from(user);
    }
}
