package com.bugeon.fintechledger.auth.service;

import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository      userRepository;
    private final PasswordEncoder     passwordEncoder;
    private final JwtTokenProvider    jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final AuditService        auditService;

    // ── Signup ────────────────────────────────────────────────────────────────

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = request.email().toLowerCase().trim();

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User   user         = User.create(email, passwordHash, request.fullName());
        userRepository.save(user);

        // Audit: SIGNUP (REQUIRES_NEW — 비즈니스 TX 커밋 후 별도 커밋)
        auditService.logSuccess(AuditAction.SIGNUP, user.getId(), email,
                "USER", user.getId(),
                String.format("{\"email\":\"%s\"}", email));

        log.info("Signup: new user registered [email={}]", email);
        return SignupResponse.from(user);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Transactional
    public TokenResponse login(LoginRequest request) {
        String email = request.email().toLowerCase().trim();

        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Audit FAILURE: 로그인 실패 (이메일 존재 여부 노출 방지 — 동일 처리)
            UUID actorId = (user != null) ? user.getId() : null;
            auditService.logFailure(AuditAction.LOGIN_FAILED, actorId, email,
                    "USER", actorId,
                    String.format("{\"reason\":\"invalid_credentials\",\"email\":\"%s\"}", email));
            log.warn("Login failed [email={}]", email);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        user.validateActive();

        refreshTokenService.revokeAllForUser(user.getId());

        List<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_USER"));

        String accessToken  = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getEmail(), authorities);
        String refreshToken = refreshTokenService.createRefreshToken(user.getId());

        // Audit: LOGIN 성공
        auditService.logSuccess(AuditAction.LOGIN, user.getId(), email,
                "USER", user.getId(),
                String.format("{\"email\":\"%s\"}", email));

        log.info("Login: user authenticated [email={}]", email);
        return TokenResponse.of(accessToken, refreshToken, jwtTokenProvider.getAccessExpiryMs());
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

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

        auditService.logSuccess(AuditAction.TOKEN_REFRESH, user.getId(), user.getEmail(),
                "USER", user.getId(), null);

        log.debug("Token refreshed for userId={}", userId);
        return TokenResponse.of(newAccessToken, newRefreshToken,
                                jwtTokenProvider.getAccessExpiryMs());
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Transactional
    public void logout(UserPrincipal principal) {
        refreshTokenService.revokeAllForUser(principal.getUserId());

        auditService.logSuccess(AuditAction.LOGOUT, principal.getUserId(), principal.getEmail(),
                "USER", principal.getUserId(), null);

        log.info("Logout: user signed out [email={}]", principal.getEmail());
    }

    // ── Me ────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public UserResponse me(UserPrincipal principal) {
        User user = userRepository.findById(principal.getUserId())
                .filter(u -> u.getStatus().name().equals("ACTIVE")
                             || u.getStatus().name().equals("SUSPENDED"))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return UserResponse.from(user);
    }
}
