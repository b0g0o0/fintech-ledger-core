package com.bugeon.fintechledger.transaction.controller;

import com.bugeon.fintechledger.auth.security.CustomUserDetailsService;
import com.bugeon.fintechledger.auth.security.JwtTokenProvider;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.transaction.domain.TransactionStatus;
import com.bugeon.fintechledger.transaction.domain.TransactionType;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.dto.TransactionResponse;
import com.bugeon.fintechledger.transaction.dto.TransferRequest;
import com.bugeon.fintechledger.transaction.dto.WithdrawRequest;
import com.bugeon.fintechledger.transaction.service.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TransactionController")
class TransactionControllerTest {

    @MockBean JwtTokenProvider         jwtTokenProvider;
    @MockBean CustomUserDetailsService customUserDetailsService;
    @MockBean TransactionService       transactionService;

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final String IDEM_KEY = UUID.randomUUID().toString();

    private UserPrincipal principal;
    private UUID          userId;
    private UUID          accountId;
    private UUID          txId;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        accountId = UUID.randomUUID();
        txId      = UUID.randomUUID();
        principal = UserPrincipal.of(userId, "user@example.com");
    }

    // ── POST /deposit ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/transactions/deposit")
    class DepositApi {

        @Test
        @DisplayName("201 — 입금 성공 (Idempotency-Key 포함)")
        void deposit_201() throws Exception {
            given(transactionService.deposit(eq(IDEM_KEY), any(DepositRequest.class), any(UserPrincipal.class)))
                    .willReturn(txResponse(TransactionType.DEPOSIT, null, accountId, "10000"));

            mockMvc.perform(post("/api/v1/transactions/deposit")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new DepositRequest(accountId, new BigDecimal("10000"), "KRW", "급여"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.amount").value(10000));
        }

        @Test
        @DisplayName("400 — Idempotency-Key 헤더 누락")
        void deposit_400_missing_idempotency_key() throws Exception {
            mockMvc.perform(post("/api/v1/transactions/deposit")
                            .with(user(principal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new DepositRequest(accountId, new BigDecimal("10000"), "KRW", null))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("400 — 금액이 0 이하")
        void deposit_400_invalid_amount() throws Exception {
            mockMvc.perform(post("/api/v1/transactions/deposit")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new DepositRequest(accountId, BigDecimal.ZERO, "KRW", null))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("400 — Currency 불일치 (H-2)")
        void deposit_400_currency_mismatch() throws Exception {
            given(transactionService.deposit(any(), any(), any()))
                    .willThrow(new BusinessException(ErrorCode.CURRENCY_MISMATCH));

            mockMvc.perform(post("/api/v1/transactions/deposit")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new DepositRequest(accountId, new BigDecimal("100"), "USD", null))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("TXN_006"));
        }

        @Test
        @DisplayName("404 — 계좌 없음")
        void deposit_404() throws Exception {
            given(transactionService.deposit(any(), any(), any()))
                    .willThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

            mockMvc.perform(post("/api/v1/transactions/deposit")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new DepositRequest(accountId, new BigDecimal("10000"), "KRW", null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACC_001"));
        }
    }

    // ── POST /withdraw ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/transactions/withdraw")
    class WithdrawApi {

        @Test
        @DisplayName("201 — 출금 성공")
        void withdraw_201() throws Exception {
            given(transactionService.withdraw(eq(IDEM_KEY), any(WithdrawRequest.class), any(UserPrincipal.class)))
                    .willReturn(txResponse(TransactionType.WITHDRAWAL, accountId, null, "5000"));

            mockMvc.perform(post("/api/v1/transactions/withdraw")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new WithdrawRequest(accountId, new BigDecimal("5000"), "KRW", "생활비"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        }

        @Test
        @DisplayName("422 — 잔액 부족")
        void withdraw_422_insufficient() throws Exception {
            given(transactionService.withdraw(any(), any(), any()))
                    .willThrow(new BusinessException(ErrorCode.INSUFFICIENT_FUNDS));

            mockMvc.perform(post("/api/v1/transactions/withdraw")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new WithdrawRequest(accountId, new BigDecimal("999999"), "KRW", null))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("TXN_001"));
        }

        @Test
        @DisplayName("403 — 타인 계좌 출금")
        void withdraw_403() throws Exception {
            given(transactionService.withdraw(any(), any(), any()))
                    .willThrow(new BusinessException(ErrorCode.ACCOUNT_ACCESS_DENIED));

            mockMvc.perform(post("/api/v1/transactions/withdraw")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new WithdrawRequest(accountId, new BigDecimal("5000"), "KRW", null))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACC_002"));
        }
    }

    // ── POST /transfer ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/transactions/transfer")
    class TransferApi {

        private UUID toAccountId;

        @BeforeEach
        void setup() { toAccountId = UUID.randomUUID(); }

        @Test
        @DisplayName("201 — 송금 성공")
        void transfer_201() throws Exception {
            given(transactionService.transfer(eq(IDEM_KEY), any(TransferRequest.class), any(UserPrincipal.class)))
                    .willReturn(txResponse(TransactionType.TRANSFER, accountId, toAccountId, "20000"));

            mockMvc.perform(post("/api/v1/transactions/transfer")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new TransferRequest(accountId, toAccountId, new BigDecimal("20000"), "KRW", "송금"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.amount").value(20000));
        }

        @Test
        @DisplayName("400 — 자기 자신 송금")
        void transfer_400_self() throws Exception {
            given(transactionService.transfer(any(), any(), any()))
                    .willThrow(new BusinessException(ErrorCode.SELF_TRANSFER_NOT_ALLOWED));

            mockMvc.perform(post("/api/v1/transactions/transfer")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new TransferRequest(accountId, accountId, new BigDecimal("10000"), "KRW", null))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("TXN_005"));
        }

        @Test
        @DisplayName("422 — 잔액 부족")
        void transfer_422_insufficient() throws Exception {
            given(transactionService.transfer(any(), any(), any()))
                    .willThrow(new BusinessException(ErrorCode.INSUFFICIENT_FUNDS));

            mockMvc.perform(post("/api/v1/transactions/transfer")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new TransferRequest(accountId, toAccountId, new BigDecimal("9999999"), "KRW", null))))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        @DisplayName("404 — 계좌 없음")
        void transfer_404() throws Exception {
            given(transactionService.transfer(any(), any(), any()))
                    .willThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

            mockMvc.perform(post("/api/v1/transactions/transfer")
                            .with(user(principal))
                            .header("Idempotency-Key", IDEM_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new TransferRequest(accountId, toAccountId, new BigDecimal("1000"), "KRW", null))))
                    .andExpect(status().isNotFound());
        }
    }

    // ── GET /transactions ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/transactions")
    class GetTransactionsApi {

        @Test
        @DisplayName("200 — 거래 내역 조회")
        void getTransactions_200() throws Exception {
            List<TransactionResponse> list = List.of(
                    txResponse(TransactionType.DEPOSIT, null, accountId, "10000"),
                    txResponse(TransactionType.WITHDRAWAL, accountId, null, "5000")
            );
            given(transactionService.getTransactions(eq(accountId), any(UserPrincipal.class), any(Pageable.class)))
                    .willReturn(new PageImpl<>(list));

            mockMvc.perform(get("/api/v1/transactions")
                            .with(user(principal))
                            .param("accountId", accountId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.length()").value(2));
        }

        @Test
        @DisplayName("403 — 타인 계좌 조회")
        void getTransactions_403() throws Exception {
            given(transactionService.getTransactions(any(), any(), any()))
                    .willThrow(new BusinessException(ErrorCode.ACCOUNT_ACCESS_DENIED));

            mockMvc.perform(get("/api/v1/transactions")
                            .with(user(principal))
                            .param("accountId", accountId.toString()))
                    .andExpect(status().isForbidden());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TransactionResponse txResponse(TransactionType type, UUID fromId, UUID toId, String amount) {
        return new TransactionResponse(txId, type, TransactionStatus.SUCCESS,
                fromId, toId, new BigDecimal(amount), "KRW", null, Instant.now());
    }
}
