package com.bugeon.fintechledger.auth.controller;

import com.bugeon.fintechledger.auth.dto.*;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.auth.service.AuthService;
import com.bugeon.fintechledger.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for Authentication and Token Management.
 *
 * <pre>
 * POST /api/v1/auth/signup   — register a new user          (public)
 * POST /api/v1/auth/login    — authenticate → token pair    (public)
 * POST /api/v1/auth/refresh  — rotate refresh token         (public, token required)
 * POST /api/v1/auth/logout   — revoke refresh tokens        (authenticated)
 * GET  /api/v1/auth/me       — current user profile         (authenticated)
 * </pre>
 *
 * All responses are wrapped in {@link ApiResponse} for a uniform envelope.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Authentication and token management")
public class AuthController {

    private final AuthService authService;

    // ── POST /signup ──────────────────────────────────────────────────────────

    @PostMapping("/signup")
    @Operation(
        summary     = "Register a new user",
        description = "Creates a new user account. Email must be unique. Password is BCrypt-hashed."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "User created successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Validation error"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "Email already registered")
    })
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @Valid @RequestBody SignupRequest request) {

        SignupResponse data = authService.signup(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(data));
    }

    // ── POST /login ───────────────────────────────────────────────────────────

    @PostMapping("/login")
    @Operation(
        summary     = "Authenticate and receive a token pair",
        description = "Validates credentials and returns a JWT access token (15 min) " +
                      "and an opaque refresh token (7 days)."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Authentication successful"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Invalid credentials"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "Account suspended")
    })
    public ResponseEntity<ApiResponse<TokenResponse>> login(
            @Valid @RequestBody LoginRequest request) {

        TokenResponse data = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    // ── POST /refresh ─────────────────────────────────────────────────────────

    @PostMapping("/refresh")
    @Operation(
        summary     = "Rotate refresh token",
        description = "Validates the provided refresh token, revokes it, and issues " +
                      "a new access token + refresh token (single-use rotation)."
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Token rotated successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Refresh token not found, revoked, or expired")
    })
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {

        TokenResponse data = authService.refresh(request);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    // ── POST /logout ──────────────────────────────────────────────────────────

    @PostMapping("/logout")
    @Operation(
        summary     = "Revoke all refresh tokens",
        description = "Revokes all active refresh tokens for the authenticated user. " +
                      "The JWT access token will expire naturally (stateless).",
        security    = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Logged out successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Missing or invalid access token")
    })
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal UserPrincipal principal) {

        authService.logout(principal);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── GET /me ───────────────────────────────────────────────────────────────

    @GetMapping("/me")
    @Operation(
        summary     = "Get current user profile",
        description = "Returns the profile of the currently authenticated user. " +
                      "Re-loads from DB to reflect any status changes.",
        security    = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Profile returned successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
            description = "Missing or invalid access token"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "User account has been deleted")
    })
    public ResponseEntity<ApiResponse<UserResponse>> me(
            @AuthenticationPrincipal UserPrincipal principal) {

        UserResponse data = authService.me(principal);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }
}
