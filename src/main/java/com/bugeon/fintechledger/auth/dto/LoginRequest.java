package com.bugeon.fintechledger.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/login}.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {}
