package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.ledger.service.LedgerService;
import com.bugeon.fintechledger.outbox.service.OutboxService;
import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.domain.TransactionStatus;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.dto.TransactionResponse;
import com.bugeon.fintechledger.transaction.dto.TransferRequest;
import com.bugeon.fintechledger.transaction.dto.WithdrawRequest;
import com.bugeon.fintechledger.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@DisplayName("TransactionService")
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock AccountRepository              accountRepository;
    @Mock TransactionRepository          transactionRepository;
    @Mock LedgerService                  ledgerService;
    @Mock AuditService                   auditService;
    @Mock OutboxService                  outboxService;
    @Mock TransactionFailureRecorder     failureRecorder;
    @Mock ApplicationEventPublisher      eventPublisher;

    @InjectMocks TransactionService transactionService;

    private UUID          userId;
    private UUID          accountId;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        accountId = UUID.randomUUID();
        principal = UserPrincipal.of(userId, "user@example.com");
        given(transactionRepository.findByIdempotencyKey(any())).willReturn(Optional.empty());
    }

    // ── Deposit ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deposit")
    class DepositTests {

        @Test
        @DisplayName("입금 성공 — PENDING → SUCCESS + Ledger CREDIT + Outbox + AFTER_COMMIT 이벤트")
        void deposit_success() {
            Account account = accountWithBalance(userId, accountId, BigDecimal.ZERO);
            given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
            given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

            TransactionResponse res = transactionService.deposit(
                    UUID.randomUUID().toString(),
                    new DepositRequest(accountId, new BigDecimal("10000"), "KRW", "급여"),
                    principal);

            assertThat(res.status()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(account.getCachedBalance()).isEqualByComparingTo("10000");

            // Ledger CREDIT 기록
            then(ledgerService).should().recordDeposit(any(), eq(accountId),
                    eq(new BigDecimal("10000")), eq(new BigDecimal("10000")), eq("KRW"), any());

            // Outbox 이벤트 저장
            then(outboxService).should().publishDeposit(any(), eq(accountId), any(), eq("KRW"));

            // AFTER_COMMIT 이벤트 발행 (Audit은 리스너가 처리)
            then(eventPublisher).should().publishEvent(
                    any(TransactionAuditEventListener.DepositCompletedEvent.class));

            // auditService.logSuccess는 직접 호출되지 않음 (AFTER_COMMIT 리스너 담당)
            then(auditService).should(never()).logSuccess(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("중복 요청 — 기존 Transaction 반환 (C-1 Idempotency)")
        void deposit_idempotent() {
            Transaction existing = Transaction.createDeposit(
                    "dup-key", accountId, new BigDecimal("10000"), "KRW", null);
            existing.markSuccess();
            given(transactionRepository.findByIdempotencyKey("dup-key"))
                    .willReturn(Optional.of(existing));

            TransactionResponse res = transactionService.deposit(
                    "dup-key",
                    new DepositRequest(accountId, new BigDecimal("10000"), "KRW", null),
                    principal);

            assertThat(res.status()).isEqualTo(TransactionStatus.SUCCESS);
            then(accountRepository).should(never()).findByIdForUpdate(any());
            then(ledgerService).should(never()).recordDeposit(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("통화 불일치 — CURRENCY_MISMATCH + FAILED 영구 저장 (H-2, C-1)")
        void deposit_currency_mismatch() {
            Account account = accountWithBalance(userId, accountId, BigDecimal.ZERO);
            given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
            given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> transactionService.deposit(
                    UUID.randomUUID().toString(),
                    new DepositRequest(accountId, new BigDecimal("100"), "USD", null),
                    principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CURRENCY_MISMATCH);

            // C-1: FAILED 상태 REQUIRES_NEW TX에서 저장
            then(failureRecorder).should().persistFailure(any(Transaction.class), any());
            // Audit FAILURE 기록
            then(auditService).should().logFailure(any(), eq(userId), any(), any(), any(), any());
        }

        @Test
        @DisplayName("계좌 없음 — ACCOUNT_NOT_FOUND + FAILED 저장")
        void deposit_account_not_found() {
            given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.empty());
            given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> transactionService.deposit(
                    UUID.randomUUID().toString(),
                    new DepositRequest(accountId, new BigDecimal("10000"), "KRW", null),
                    principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);

            then(failureRecorder).should().persistFailure(any(), any());
            then(auditService).should().logFailure(any(), any(), any(), any(), any(), any());
        }
    }

    // ── Withdraw ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("withdraw")
    class WithdrawTests {

        @Test
        @DisplayName("출금 성공 — PENDING → SUCCESS + Ledger DEBIT + Outbox + AFTER_COMMIT 이벤트")
        void withdraw_success() {
            Account account = accountWithBalance(userId, accountId, new BigDecimal("50000"));
            given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
            given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

            TransactionResponse res = transactionService.withdraw(
                    UUID.randomUUID().toString(),
                    new WithdrawRequest(accountId, new BigDecimal("20000"), "KRW", "생활비"),
                    principal);

            assertThat(res.status()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(account.getCachedBalance()).isEqualByComparingTo("30000");

            then(ledgerService).should().recordWithdrawal(any(), eq(accountId),
                    eq(new BigDecimal("20000")), eq(new BigDecimal("30000")), eq("KRW"), any());
            then(outboxService).should().publishWithdrawal(any(), eq(accountId), any(), eq("KRW"));
            then(eventPublisher).should().publishEvent(
                    any(TransactionAuditEventListener.WithdrawalCompletedEvent.class));
        }

        @Test
        @DisplayName("잔액 부족 — INSUFFICIENT_FUNDS + FAILED 영구 저장 (C-1, M-1)")
        void withdraw_insufficient_funds() {
            Account account = accountWithBalance(userId, accountId, new BigDecimal("5000"));
            given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
            given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> transactionService.withdraw(
                    UUID.randomUUID().toString(),
                    new WithdrawRequest(accountId, new BigDecimal("10000"), "KRW", null),
                    principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);

            // C-1: FAILED 영구 저장
            then(failureRecorder).should().persistFailure(any(), any());
            // M-1: Audit FAILURE
            then(auditService).should().logFailure(any(), eq(userId), any(), any(), any(), any());
            // Outbox 이벤트 없음
            then(outboxService).should(never()).publishWithdrawal(any(), any(), any(), any());
        }

        @Test
        @DisplayName("타인 계좌 출금 — ACCOUNT_ACCESS_DENIED + FAILED 저장")
        void withdraw_access_denied() {
            UUID otherUserId = UUID.randomUUID();
            Account account = accountWithBalance(otherUserId, accountId, new BigDecimal("50000"));
            given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
            given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> transactionService.withdraw(
                    UUID.randomUUID().toString(),
                    new WithdrawRequest(accountId, new BigDecimal("10000"), "KRW", null),
                    principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_ACCESS_DENIED);

            then(failureRecorder).should().persistFailure(any(), any());
        }
    }

    // ── Transfer ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("transfer")
    class TransferTests {

        private UUID fromAccountId;
        private UUID toAccountId;

        @BeforeEach
        void setUp() {
            fromAccountId = UUID.randomUUID();
            toAccountId   = UUID.randomUUID();
        }

        @Test
        @DisplayName("송금 성공 — 잔액 이동 + LedgerEntry 2건 + Outbox + AFTER_COMMIT 이벤트")
        void transfer_success() {
            Account fromAccount = accountWithBalance(userId, fromAccountId, new BigDecimal("100000"));
            Account toAccount   = accountWithBalance(UUID.randomUUID(), toAccountId, BigDecimal.ZERO);

            List<UUID> sortedIds = List.of(fromAccountId, toAccountId)
                    .stream().sorted(UUID::compareTo).toList();
            List<Account> locked = sortedIds.stream()
                    .map(id -> id.equals(fromAccountId) ? fromAccount : toAccount).toList();

            given(accountRepository.findAllByIdInForUpdate(any())).willReturn(locked);
            given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

            TransactionResponse res = transactionService.transfer(
                    UUID.randomUUID().toString(),
                    new TransferRequest(fromAccountId, toAccountId, new BigDecimal("30000"), "KRW", "송금"),
                    principal);

            assertThat(res.status()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(fromAccount.getCachedBalance()).isEqualByComparingTo("70000");
            assertThat(toAccount.getCachedBalance()).isEqualByComparingTo("30000");

            // Double Entry 검증
            then(ledgerService).should().recordTransfer(any(),
                    eq(fromAccountId), eq(new BigDecimal("70000")),
                    eq(toAccountId),   eq(new BigDecimal("30000")),
                    eq(new BigDecimal("30000")), eq("KRW"), eq("송금"));

            then(outboxService).should().publishTransfer(
                    any(), eq(fromAccountId), eq(toAccountId), any(), eq("KRW"));
            then(eventPublisher).should().publishEvent(
                    any(TransactionAuditEventListener.TransferCompletedEvent.class));
        }

        @Test
        @DisplayName("자기 자신 송금 — SELF_TRANSFER_NOT_ALLOWED (계좌 조회 없음)")
        void transfer_self() {
            assertThatThrownBy(() -> transactionService.transfer(
                    UUID.randomUUID().toString(),
                    new TransferRequest(fromAccountId, fromAccountId, new BigDecimal("10000"), "KRW", null),
                    principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SELF_TRANSFER_NOT_ALLOWED);

            then(accountRepository).should(never()).findAllByIdInForUpdate(any());
            then(transactionRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("잔액 부족 — INSUFFICIENT_FUNDS + FAILED 영구 저장 + Audit FAILURE")
        void transfer_insufficient() {
            Account fromAccount = accountWithBalance(userId, fromAccountId, new BigDecimal("1000"));
            Account toAccount   = accountWithBalance(UUID.randomUUID(), toAccountId, BigDecimal.ZERO);

            List<UUID> sortedIds = List.of(fromAccountId, toAccountId)
                    .stream().sorted(UUID::compareTo).toList();
            List<Account> locked = sortedIds.stream()
                    .map(id -> id.equals(fromAccountId) ? fromAccount : toAccount).toList();

            given(accountRepository.findAllByIdInForUpdate(any())).willReturn(locked);
            given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

            assertThatThrownBy(() -> transactionService.transfer(
                    UUID.randomUUID().toString(),
                    new TransferRequest(fromAccountId, toAccountId, new BigDecimal("5000"), "KRW", null),
                    principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);

            then(failureRecorder).should().persistFailure(any(), any());
            then(auditService).should().logFailure(any(), eq(userId), any(), any(), any(), any());
        }

        @Test
        @DisplayName("중복 요청 — 기존 Transaction 반환 (C-1)")
        void transfer_idempotent() {
            Transaction existing = Transaction.createTransfer(
                    "dup-key", fromAccountId, toAccountId,
                    new BigDecimal("30000"), "KRW", null);
            existing.markSuccess();
            given(transactionRepository.findByIdempotencyKey("dup-key"))
                    .willReturn(Optional.of(existing));

            TransactionResponse res = transactionService.transfer(
                    "dup-key",
                    new TransferRequest(fromAccountId, toAccountId, new BigDecimal("30000"), "KRW", null),
                    principal);

            assertThat(res.status()).isEqualTo(TransactionStatus.SUCCESS);
            then(accountRepository).should(never()).findAllByIdInForUpdate(any());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Account accountWithBalance(UUID ownerId, UUID id, BigDecimal balance) {
        Account account = Account.create(ownerId, "1001-0000-000001", "Test", "KRW");
        account.increaseCachedBalance(balance);
        try {
            var f = Account.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(account, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return account;
    }
}
