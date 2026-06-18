package com.bugeon.fintechledger.common.exception;

import com.bugeon.fintechledger.common.web.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates every exception type into a uniform {@link ApiResponse} JSON error.
 *
 * <pre>
 * BusinessException           → structured error code + message
 * MethodArgumentNotValid      → 400 with per-field validation details
 * HttpMessageNotReadable      → 400 malformed JSON body
 * MissingRequestParameter     → 400 missing query param
 * MethodArgumentTypeMismatch  → 400 wrong param type
 * AccessDeniedException       → 403 (Spring Security @PreAuthorize)
 * Exception (catch-all)       → 500, internal message suppressed
 * </pre>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── BusinessException ─────────────────────────────────────────────────────

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        log.debug("BusinessException [{}]: {}", code.getCode(), ex.getMessage());

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        ex.getMessage(),
                        null
                )));
    }

    // ── Validation: @Valid on @RequestBody ────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null
                                ? fe.getDefaultMessage()
                                : "Invalid value",
                        (first, second) -> first      // keep first error per field
                ));

        log.debug("Validation failed: {}", fieldErrors);

        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        code.getDefaultMessage(),
                        fieldErrors
                )));
    }

    // ── Malformed JSON body ───────────────────────────────────────────────────

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(
            HttpMessageNotReadableException ex) {

        log.debug("Unreadable request body: {}", ex.getMessage());

        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        "Request body is missing or malformed",
                        null
                )));
    }

    // ── Missing query / path parameters ──────────────────────────────────────

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(
            MissingServletRequestParameterException ex) {

        log.debug("Missing request parameter: {}", ex.getParameterName());

        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        "Required parameter '" + ex.getParameterName() + "' is missing",
                        null
                )));
    }

    // ── Wrong parameter type ──────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {

        log.debug("Type mismatch for parameter [{}]: {}", ex.getName(), ex.getMessage());

        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        "Parameter '" + ex.getName() + "' has an invalid value",
                        null
                )));
    }

    // ── Spring Security: 403 Forbidden ────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.debug("Access denied: {}", ex.getMessage());

        return ResponseEntity
                .status(403)
                .body(ApiResponse.fail(new ApiResponse.Error(
                        "GEN_403",
                        "You do not have permission to access this resource",
                        null
                )));
    }

    // ── Catch-all ─────────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);

        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.fail(new ApiResponse.Error(
                        code.getCode(),
                        code.getDefaultMessage(),
                        null       // never leak internal details
                )));
    }
}
