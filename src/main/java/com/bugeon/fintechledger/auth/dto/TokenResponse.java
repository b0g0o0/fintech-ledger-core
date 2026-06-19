package com.bugeon.fintechledger.auth.dto;

/**
 * Response body for login and token-refresh operations.
 *
 * <pre>
 * {
 *   "accessToken":  "eyJhbGci...",   // JWT — send as Authorization: Bearer header
 *   "refreshToken": "550e8400-...",  // Opaque UUID — store securely; single-use
 *   "tokenType":    "Bearer",
 *   "expiresIn":    900              // seconds until accessToken expires
 * }
 * </pre>
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long   expiresIn          // seconds
) {
    /** Convenience factory converting milliseconds to the seconds field. */
    public static TokenResponse of(String accessToken, String refreshToken, long accessExpiryMs) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", accessExpiryMs / 1000);
    }
}
