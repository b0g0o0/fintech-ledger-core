package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.ledger.service.LedgerService;
import com.bugeon.fintechledger.outbox.service.OutboxService;
import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.domain.TransactionStatus;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.dto.TransactionResponse;
import com.bugeon.fintechledger.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

/**
 * Idempotency 동시성 테스트.
 *
 * 사용자가 송금/입금 버튼을 5회, 10회, 100회 눌러도
 * 실제 거래는 1회만 실행되어야 함을 검증한다.
 *
 * 이 단위 테스트는 서비스 레이어의 idempotency 로직을 검증한다.
 * 실제 DB UNIQUE 제약과의 경쟁 조건은 통합 테스트에서 검증해야 하나,
 * 서비스 레이어가 이미 커밋된 Transaction을 올바르게 반환하는지 확인한다.
 */
@DisplayName("Idempotency 동시성 테스트")
@ExtendWith(MockitoExtension.class)
class IdempotencyConcurrencyTest {

    @Mock AccountRepository          accountRepository;
    @Mock TransactionRepository      transactionRepository;
    @Mock LedgerService              ledgerService;
    @Mock AuditService               auditService;
    @Mock OutboxService              outboxService;
    @Mock TransactionFailureRecorder failureRecorder;
    @Mock ApplicationEventPublisher  eventPublisher;

    @InjectMocks TransactionService transactionService;

    private UUID          userId;
    private UUID          accountId;
    private String        idempotencyKey;

    @BeforeEach
    void setUp() {
        userId         = UUID.randomUUID();
        accountId      = UUID.randomUUID();
        idempotencyKey = UUID.randomUUID().toString();
    }

    // ── 단일 스레드: 동일 키 5회 호출 ─────────────────────────────────────────

    @Test
    @DisplayName("동일 Idempotency-Key로 5회 호출 — 응답은 항상 동일한 Transaction")
    void sameKey_5Calls_sameResult() {
        // 첫 번째 호출: DB에 없음
        // 두 번째 이후: 이미 존재하는 Transaction 반환
        Transaction existing = Transaction.createDeposit(
                idempotencyKey, accountId, new BigDecimal("10000"), "KRW", null);
        existing.markSuccess();

        // 첫 호출: empty → 저장 → 성공
        // 이후 호출: 존재 → 그대로 반환
        AtomicInteger callCount = new AtomicInteger(0);
        given(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .willAnswer(inv -> {
                    int count = callCount.incrementAndGet();
                    return count == 1 ? Optional.empty() : Optional.of(existing);
                });

        Account account = accountWithBalance(userId, accountId, BigDecimal.ZERO);
        given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
        given(transactionRepository.save(any(Transaction.class))).willReturn(existing);

        DepositRequest request = new DepositRequest(accountId, new BigDecimal("10000"), "KRW", null);

        List<TransactionResponse> responses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            responses.add(transactionService.deposit(idempotencyKey, request,
                    com.bugeon.fintechledger.auth.security.UserPrincipal.of(userId, "u@e.com")));
        }

        // 모든 응답이 SUCCESS여야 함
        assertThat(responses).hasSize(5);
        assertThat(responses).allMatch(r -> r.status() == TransactionStatus.SUCCESS);

        // 잔액이 1회만 증가해야 함 (서비스는 두 번째부터 저장 안 함)
        // save()는 최초 1회만 호출 (이후는 기존 응답 반환)
        then(transactionRepository).should(times(1)).save(any(Transaction.class));
        then(ledgerService).should(times(1)).recordDeposit(any(), any(), any(), any(), any(), any());
    }

    // ── 멀티 스레드: 동일 키 10개 스레드 동시 호출 ───────────────────────────

    @Test
    @DisplayName("동일 Idempotency-Key로 10개 스레드 동시 호출 — 단 1회만 처리")
    void sameKey_10ConcurrentCalls_onlyOnceProcessed() throws Exception {
        int threadCount = 10;
        ExecutorService executor   = Executors.newFixedThreadPool(threadCount);
        CountDownLatch  startLatch = new CountDownLatch(1);
        CountDownLatch  doneLatch  = new CountDownLatch(threadCount);

        // 첫 번째 스레드만 empty, 나머지는 existing 반환 시뮬레이션
        Transaction existing = Transaction.createDeposit(
                idempotencyKey, accountId, new BigDecimal("10000"), "KRW", null);
        existing.markSuccess();

        AtomicInteger callCount = new AtomicInteger(0);
        AtomicReference<Transaction> savedTx = new AtomicReference<>(null);

        given(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .willAnswer(inv -> {
                    int count = callCount.getAndIncrement();
                    // 첫 번째 호출만 empty — 실제 DB에서는 UNIQUE violation이 방어
                    return count == 0 ? Optional.empty() : Optional.of(existing);
                });

        Account account = accountWithBalance(userId, accountId, BigDecimal.ZERO);
        given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
        given(transactionRepository.save(any(Transaction.class))).willAnswer(inv -> {
            savedTx.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        DepositRequest request = new DepositRequest(accountId, new BigDecimal("10000"), "KRW", null);
        var principal = com.bugeon.fintechledger.auth.security.UserPrincipal.of(userId, "u@e.com");

        List<Future<TransactionResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();  // 동시 시작
                    return transactionService.deposit(idempotencyKey, request, principal);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();  // 모든 스레드 동시 시작
        doneLatch.await();

        List<TransactionResponse> responses = new ArrayList<>();
        for (Future<TransactionResponse> f : futures) {
            responses.add(f.get());
        }

        // 모든 응답이 SUCCESS
        assertThat(responses).hasSize(threadCount);
        assertThat(responses).allMatch(r -> r.status() == TransactionStatus.SUCCESS);

        // 잔액 증가는 최대 1회 (서비스 레이어 보호)
        // 실제 DB에서는 UNIQUE constraint가 추가로 방어
        verify(ledgerService, atMost(1)).recordDeposit(any(), any(), any(), any(), any(), any());
        verify(transactionRepository, atMost(1)).save(any(Transaction.class));

        executor.shutdown();
    }

    // ── 서로 다른 키: 독립 처리 보장 ─────────────────────────────────────────

    @Test
    @DisplayName("서로 다른 Idempotency-Key — 각각 독립적으로 처리")
    void differentKeys_eachProcessedOnce() {
        String key1 = UUID.randomUUID().toString();
        String key2 = UUID.randomUUID().toString();

        Account account = accountWithBalance(userId, accountId, new BigDecimal("100000"));
        given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
        given(transactionRepository.findByIdempotencyKey(key1)).willReturn(Optional.empty());
        given(transactionRepository.findByIdempotencyKey(key2)).willReturn(Optional.empty());
        given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

        var principal = com.bugeon.fintechledger.auth.security.UserPrincipal.of(userId, "u@e.com");
        DepositRequest r1 = new DepositRequest(accountId, new BigDecimal("10000"), "KRW", "1차");
        DepositRequest r2 = new DepositRequest(accountId, new BigDecimal("20000"), "KRW", "2차");

        transactionService.deposit(key1, r1, principal);
        transactionService.deposit(key2, r2, principal);

        // 각각 1회씩 Ledger 기록 = 총 2회
        verify(ledgerService, times(2)).recordDeposit(any(), any(), any(), any(), any(), any());
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
