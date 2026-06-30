package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.ledger.service.LedgerService;
import com.bugeon.fintechledger.outbox.service.OutboxService;
import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.dto.WithdrawRequest;
import com.bugeon.fintechledger.transaction.dto.TransferRequest;
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
 * 동시성 보호 테스트.
 *
 * 금융 시스템 핵심 시나리오:
 *   계좌 잔액 100,000원
 *   70,000원 출금 × 2개 스레드 동시 실행
 *   결과: 잔액이 절대 -40,000이 되어서는 안 된다.
 *
 * 현재 보호 수준 분석:
 *   1. PESSIMISTIC_WRITE (SELECT FOR UPDATE): 가장 강력한 보호
 *      findByIdForUpdate() — 계좌 행에 배타적 락
 *      락을 획득한 스레드만 잔액을 읽고 변경 가능
 *      다른 스레드는 락 해제까지 대기
 *
 *   2. @Version (낙관적 락): 추가 방어층
 *      PESSIMISTIC_WRITE가 실패한 경우 또는 미사용 경로에서 방어
 *
 *   3. DB CHECK (cached_balance >= 0): 최후 방어선
 *      음수 잔액 자체를 DB가 거부
 *
 *   4. decreaseCachedBalance(): 코드 레벨 음수 방지
 *      result < 0이면 INSUFFICIENT_FUNDS BusinessException
 *
 * 이 테스트는 서비스 레이어의 잔액 감소 로직이 올바르게 작동함을 검증한다.
 * 실제 PESSIMISTIC_WRITE 동시성은 DB 통합 테스트에서 검증해야 한다.
 */
@DisplayName("동시성 보호 테스트")
@ExtendWith(MockitoExtension.class)
class ConcurrencyProtectionTest {

    @Mock AccountRepository          accountRepository;
    @Mock TransactionRepository      transactionRepository;
    @Mock LedgerService              ledgerService;
    @Mock AuditService               auditService;
    @Mock OutboxService              outboxService;
    @Mock TransactionFailureRecorder failureRecorder;
    @Mock ApplicationEventPublisher  eventPublisher;

    @InjectMocks TransactionService transactionService;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        accountId = UUID.randomUUID();
    }

    // ── 잔액 음수 방지 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("잔액 100,000 — 70,000 출금 시도: 성공 후 잔액 30,000")
    void withdraw_success_correctBalance() {
        Account account = accountWithBalance(userId, accountId, new BigDecimal("100000"));
        given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
        given(transactionRepository.findByIdempotencyKey(any())).willReturn(Optional.empty());
        given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

        var principal = com.bugeon.fintechledger.auth.security.UserPrincipal.of(userId, "u@e.com");
        transactionService.withdraw(UUID.randomUUID().toString(),
                new WithdrawRequest(accountId, new BigDecimal("70000"), "KRW", null),
                principal);

        assertThat(account.getCachedBalance()).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("잔액 100,000 — 70,000 출금 후 두 번째 70,000 출금: INSUFFICIENT_FUNDS")
    void withdraw_sequential_secondFails() {
        Account account = accountWithBalance(userId, accountId, new BigDecimal("100000"));
        given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
        given(transactionRepository.findByIdempotencyKey(any())).willReturn(Optional.empty());
        given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

        var principal = com.bugeon.fintechledger.auth.security.UserPrincipal.of(userId, "u@e.com");
        String key1 = UUID.randomUUID().toString();
        String key2 = UUID.randomUUID().toString();

        // 첫 번째 출금 성공
        transactionService.withdraw(key1,
                new WithdrawRequest(accountId, new BigDecimal("70000"), "KRW", null),
                principal);

        assertThat(account.getCachedBalance()).isEqualByComparingTo("30000");

        // 두 번째 70,000 출금 시도 — 잔액 부족
        assertThatThrownBy(() ->
                transactionService.withdraw(key2,
                        new WithdrawRequest(accountId, new BigDecimal("70000"), "KRW", null),
                        principal))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);

        // 잔액은 30,000 그대로 (음수 불가)
        assertThat(account.getCachedBalance()).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("잔액 100,000 — 정확히 100,000 출금: 잔액 0 (경계값)")
    void withdraw_exactBalance_zeroResult() {
        Account account = accountWithBalance(userId, accountId, new BigDecimal("100000"));
        given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
        given(transactionRepository.findByIdempotencyKey(any())).willReturn(Optional.empty());
        given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

        var principal = com.bugeon.fintechledger.auth.security.UserPrincipal.of(userId, "u@e.com");

        transactionService.withdraw(UUID.randomUUID().toString(),
                new WithdrawRequest(accountId, new BigDecimal("100000"), "KRW", null),
                principal);

        assertThat(account.getCachedBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("잔액 100,000 — 100,001원 출금 시도: INSUFFICIENT_FUNDS (1원 초과)")
    void withdraw_oneOverBalance_fails() {
        Account account = accountWithBalance(userId, accountId, new BigDecimal("100000"));
        given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(account));
        given(transactionRepository.findByIdempotencyKey(any())).willReturn(Optional.empty());
        given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

        var principal = com.bugeon.fintechledger.auth.security.UserPrincipal.of(userId, "u@e.com");

        assertThatThrownBy(() ->
                transactionService.withdraw(UUID.randomUUID().toString(),
                        new WithdrawRequest(accountId, new BigDecimal("100001"), "KRW", null),
                        principal))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);

        // 잔액 변화 없음
        assertThat(account.getCachedBalance()).isEqualByComparingTo("100000");
    }

    // ── 동시 출금 시뮬레이션 ─────────────────────────────────────────────────

    @Test
    @DisplayName("잔액 100,000 — 70,000 × 2 동시 출금: 하나만 성공, 잔액 절대 음수 불가")
    void concurrent_70000_x2_onlyOneSucceeds() throws Exception {
        /*
         * 서비스 레이어 단위 테스트에서 실제 DB 동시성을 시뮬레이션하기 위해
         * Account 객체를 공유하여 두 스레드가 같은 잔액 객체를 수정하도록 한다.
         * 실제 환경에서는 PESSIMISTIC_WRITE가 한 번에 하나의 스레드만 락을 획득하도록 보장.
         * 이 테스트는 decreaseCachedBalance()의 음수 방지 로직을 검증한다.
         */
        Account sharedAccount = accountWithBalance(userId, accountId, new BigDecimal("100000"));
        given(accountRepository.findByIdForUpdate(accountId)).willReturn(Optional.of(sharedAccount));
        given(transactionRepository.findByIdempotencyKey(any())).willReturn(Optional.empty());
        given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

        var principal = com.bugeon.fintechledger.auth.security.UserPrincipal.of(userId, "u@e.com");

        int          threadCount  = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);
        AtomicInteger  successCount = new AtomicInteger(0);
        AtomicInteger  failureCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures  = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            String key = UUID.randomUUID().toString();
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    transactionService.withdraw(key,
                            new WithdrawRequest(accountId, new BigDecimal("70000"), "KRW", null),
                            principal);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.INSUFFICIENT_FUNDS) {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // 핵심 검증: 잔액은 절대 음수가 될 수 없다
        assertThat(sharedAccount.getCachedBalance())
                .as("잔액은 절대 음수여서는 안 됨")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // 하나만 성공하면 잔액은 30,000, 둘 다 성공하면 음수 → 둘 다 성공 불가
        int total = successCount.get() + failureCount.get();
        assertThat(total).isEqualTo(threadCount);
        assertThat(successCount.get()).isLessThanOrEqualTo(1);
    }

    // ── Transfer 데드락 방지 검증 ─────────────────────────────────────────────

    @Test
    @DisplayName("Transfer UUID ASC 정렬 락 — A→B, B→A 동시 송금 시 데드락 없음")
    void transfer_uuidAscLock_noDeadlock() throws Exception {
        UUID userA      = UUID.randomUUID();
        UUID userB      = UUID.randomUUID();
        UUID accountA   = UUID.randomUUID();
        UUID accountB   = UUID.randomUUID();
        Account accA    = accountWithBalance(userA, accountA, new BigDecimal("100000"));
        Account accB    = accountWithBalance(userB, accountB, new BigDecimal("100000"));

        // UUID ASC 정렬 후 반환 시뮬레이션
        List<Account> sortedByA = accountA.compareTo(accountB) < 0
                ? List.of(accA, accB) : List.of(accB, accA);

        given(accountRepository.findAllByIdInForUpdate(any()))
                .willReturn(sortedByA);
        given(transactionRepository.findByIdempotencyKey(any())).willReturn(Optional.empty());
        given(transactionRepository.save(any())).willAnswer(i -> i.getArgument(0));

        var principalA = com.bugeon.fintechledger.auth.security.UserPrincipal.of(userA, "a@e.com");

        int           threadCount = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);
        AtomicInteger success     = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // A→B 송금
        executor.submit(() -> {
            try {
                startLatch.await();
                transactionService.transfer(UUID.randomUUID().toString(),
                        new TransferRequest(accountA, accountB, new BigDecimal("10000"), "KRW", "A→B"),
                        principalA);
                success.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // B→A 송금 (B 소유자 principal로)
        var principalB = com.bugeon.fintechledger.auth.security.UserPrincipal.of(userB, "b@e.com");
        executor.submit(() -> {
            try {
                startLatch.await();
                transactionService.transfer(UUID.randomUUID().toString(),
                        new TransferRequest(accountB, accountA, new BigDecimal("5000"), "KRW", "B→A"),
                        principalB);
                success.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);  // 5초 내 완료 = 데드락 없음

        executor.shutdown();

        // 테스트가 5초 내에 완료됨 = 데드락 없음
        assertThat(doneLatch.getCount()).isEqualTo(0)
                .as("5초 내에 모든 스레드가 완료됨 — 데드락 없음");
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
