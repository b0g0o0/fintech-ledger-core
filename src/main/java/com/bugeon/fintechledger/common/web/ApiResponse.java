package com.bugeon.fintechledger.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;

/**
 * Uniform API response envelope returned by all endpoints.
 * <pre>
 * Success: { "success": true,  "data": {...},  "error": null,  "timestamp": "..." }
 * Failure: { "success": false, "data": null,   "error": {...}, "timestamp": "..." }
 * </pre>
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final T       data;
    private final Error   error;
    private final Instant timestamp;

    private ApiResponse(boolean success, T data, Error error) {
        this.success   = success;
        this.data      = data;
        this.error     = error;
        this.timestamp = Instant.now();
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(Error error) {
        return new ApiResponse<>(false, null, error);
    }

    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Error {
        private final String code;
        private final String message;
        private final Object details;   // validation field errors, etc.

        public Error(String code, String message, Object details) {
            this.code    = code;
            this.message = message;
            this.details = details;
        }
    }
}
