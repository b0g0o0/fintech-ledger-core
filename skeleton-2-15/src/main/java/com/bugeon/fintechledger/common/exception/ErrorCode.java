package com.bugeon.fintechledger.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Centralised error catalogue.
 *
 * Every {@link BusinessException} carries one of these codes.
 * The {@link com.bugeon.fintechledger.common.exception.GlobalExceptionHandler}
 * maps the code's {@link #httpStatus} to the HTTP response status.
 *
 * Code prefixes:
 *   AUTH_xxx — authentication / identity
 *   ACC_xxx  — virtual account
 *   TXN_xxx  — transaction / money movement
 *   LDG_xxx  — ledger invariant
 *   IDMP_xxx — idempotency
 *   GEN_xxx  — general / infrastructure
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ── Auth ──────────────────────────────────────────────────────────────────
    INVALID_CREDENTIALS     (HttpStatus.UNAUTHORIZED,           "AUTH_001", "Invalid email or password"),
    TOKEN_EXPIRED           (HttpStatus.UNAUTHORIZED,           "AUTH_002", "Access token has expired"),
    TOKEN_INVALID           (HttpStatus.UNAUTHORIZED,           "AUTH_003", "Access token is invalid"),
    REFRESH_TOKEN_NOT_FOUND (HttpStatus.UNAUTHORIZED,           "AUTH_004", "Refresh token not found or already used"),
    REFRESH_TOKEN_REVOKED   (HttpStatus.UNAUTHORIZED,           "AUTH_005", "Refresh token has been revoked"),
    DUPLICATE_EMAIL         (HttpStatus.CONFLICT,               "AUTH_006", "Email is already registered"),
    USER_NOT_FOUND          (HttpStatus.NOT_FOUND,              "AUTH_007", "User not found"),
    USER_SUSPENDED          (HttpStatus.FORBIDDEN,              "AUTH_008", "User account is suspended"),

    // ── Account ───────────────────────────────────────────────────────────────
    ACCOUNT_NOT_FOUND       (HttpStatus.NOT_FOUND,              "ACC_001",  "Account not found"),
    ACCOUNT_ACCESS_DENIED   (HttpStatus.FORBIDDEN,              "ACC_002",  "Access to this account is denied"),
    ACCOUNT_FROZEN          (HttpStatus.UNPROCESSABLE_ENTITY,   "ACC_003",  "Account is frozen"),
    ACCOUNT_CLOSED          (HttpStatus.UNPROCESSABLE_ENTITY,   "ACC_004",  "Account is closed"),

    // ── Transaction ───────────────────────────────────────────────────────────
    INSUFFICIENT_FUNDS      (HttpStatus.UNPROCESSABLE_ENTITY,   "TXN_001",  "Insufficient funds"),
    SAME_ACCOUNT_TRANSFER   (HttpStatus.BAD_REQUEST,            "TXN_002",  "Cannot transfer to the same account"),
    TRANSACTION_NOT_FOUND   (HttpStatus.NOT_FOUND,              "TXN_003",  "Transaction not found"),
    INVALID_AMOUNT          (HttpStatus.BAD_REQUEST,            "TXN_004",  "Amount must be greater than zero"),
    SELF_TRANSFER_NOT_ALLOWED (HttpStatus.BAD_REQUEST,          "TXN_005",  "Cannot transfer to the same account"),
    CURRENCY_MISMATCH         (HttpStatus.BAD_REQUEST,          "TXN_006",  "Transaction currency does not match account currency"),
    IDEMPOTENCY_DUPLICATE     (HttpStatus.OK,                   "TXN_007",  "Duplicate request — returning existing result"),

    // ── Ledger ────────────────────────────────────────────────────────────────
    LEDGER_INVARIANT_VIOLATED (HttpStatus.INTERNAL_SERVER_ERROR, "LDG_001", "Ledger double-entry invariant violated"),

    // ── Idempotency ───────────────────────────────────────────────────────────
    IDEMPOTENCY_KEY_MISSING  (HttpStatus.BAD_REQUEST,            "IDMP_001", "Idempotency-Key header is required"),
    IDEMPOTENCY_KEY_CONFLICT (HttpStatus.CONFLICT,               "IDMP_002", "Idempotency key reused with different payload"),

    // ── General ───────────────────────────────────────────────────────────────
    VALIDATION_ERROR         (HttpStatus.BAD_REQUEST,            "GEN_001",  "Request validation failed"),
    INTERNAL_SERVER_ERROR    (HttpStatus.INTERNAL_SERVER_ERROR,  "GEN_002",  "An unexpected error occurred");

    private final HttpStatus httpStatus;
    private final String     code;
    private final String     defaultMessage;
}
