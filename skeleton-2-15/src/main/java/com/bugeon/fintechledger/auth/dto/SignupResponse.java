package com.bugeon.fintechledger.auth.dto;

import com.bugeon.fintechledger.auth.domain.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code POST /api/v1/auth/signup}.
 * Never exposes passwordHash or internal status fields.
 */
public record SignupResponse(
        UUID    userId,
        String  email,
        String  fullName,
        Instant createdAt
) {
    public static SignupResponse from(User user) {
        return new SignupResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getCreatedAt()
        );
    }
}
