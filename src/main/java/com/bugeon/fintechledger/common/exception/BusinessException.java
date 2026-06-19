package com.bugeon.fintechledger.common.exception;

import lombok.Getter;

/**
 * Domain-level exception mapped to a structured HTTP error response
 * by {@link GlobalExceptionHandler}.
 *
 * Usage:
 * <pre>{@code
 * throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
 * throw new BusinessException(ErrorCode.USER_NOT_FOUND, "User " + id + " not found");
 * }</pre>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /** Uses the {@link ErrorCode}'s default message. */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    /** Overrides the message with a context-specific detail string. */
    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }
}
