package com.bugeon.fintechledger.auth.dto;

import com.bugeon.fintechledger.auth.domain.User;
import com.bugeon.fintechledger.auth.domain.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code GET /api/v1/auth/me}.
 */
public record UserResponse(
        UUID       userId,
        String     email,
        String     fullName,
        UserStatus status,
        Instant    createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }
}
