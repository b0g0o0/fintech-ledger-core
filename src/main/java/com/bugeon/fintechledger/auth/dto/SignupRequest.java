package com.bugeon.fintechledger.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/signup}.
 *
 * BCrypt max input length is 72 bytes; passwords longer than this are silently truncated,
 * so we validate the upper bound explicitly.
 */
public record SignupRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72,
              message = "Password must be between 8 and 72 characters")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(min = 2, max = 100,
              message = "Full name must be between 2 and 100 characters")
        String fullName
) {}
