package com.bugeon.fintechledger.transaction.controller;

import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.common.web.ApiResponse;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.dto.TransactionResponse;
import com.bugeon.fintechledger.transaction.dto.TransferRequest;
import com.bugeon.fintechledger.transaction.dto.WithdrawRequest;
import com.bugeon.fintechledger.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Transaction REST Controller.
 *
 * C-1 FIX: 모든 쓰기 작업은 클라이언트가 제공하는 Idempotency-Key 헤더를 필수로 요구한다.
 * 동일 키로 재시도 시 기존 응답을 반환하여 중복 거래를 방지한다.
 *
 * Idempotency-Key 규칙:
 *   - 클라이언트가 UUID v4 형식으로 생성
 *   - 동일 요청 재시도 시 동일 키 사용
 *   - 새로운 거래에는 새 키 사용
 *   - 최대 64자
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "입출금 및 송금 처리")
@SecurityRequirement(name = "bearerAuth")
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/deposit")
    @Operation(summary = "입금", description = "계좌에 금액을 입금합니다. Idempotency-Key 헤더 필수.")
    public ResponseEntity<ApiResponse<TransactionResponse>> deposit(
            @Parameter(description = "중복 방지 키 (UUID)", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DepositRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        validateIdempotencyKey(idempotencyKey);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(transactionService.deposit(idempotencyKey, request, principal)));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "출금", description = "계좌에서 금액을 출금합니다. Idempotency-Key 헤더 필수.")
    public ResponseEntity<ApiResponse<TransactionResponse>> withdraw(
            @Parameter(description = "중복 방지 키 (UUID)", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        validateIdempotencyKey(idempotencyKey);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(transactionService.withdraw(idempotencyKey, request, principal)));
    }

    @PostMapping("/transfer")
    @Operation(summary = "송금", description = "다른 계좌로 금액을 이체합니다. Idempotency-Key 헤더 필수.")
    public ResponseEntity<ApiResponse<TransactionResponse>> transfer(
            @Parameter(description = "중복 방지 키 (UUID)", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        validateIdempotencyKey(idempotencyKey);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(transactionService.transfer(idempotencyKey, request, principal)));
    }

    @GetMapping
    @Operation(summary = "거래 내역 조회", description = "계좌의 거래 내역을 페이지 단위로 조회합니다.")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getTransactions(
            @RequestParam UUID accountId,
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                transactionService.getTransactions(accountId, principal, pageable)));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISSING);
        }
    }
}
