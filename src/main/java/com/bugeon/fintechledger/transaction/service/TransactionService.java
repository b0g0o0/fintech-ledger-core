package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.ledger.service.LedgerService;
import com.bugeon.fintechledger.outbox.service.OutboxService;
import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.dto.TransactionResponse;
import com.bugeon.fintechledger.transaction.dto.TransferRequest;
import com.bugeon.fintechledger.transaction.dto.WithdrawRequest;
import com.bugeon.fintechledger.transaction.repository.TransactionRepository;
import com.bugeon.fintechledger.transaction.service.TransactionAuditEventListener.DepositCompletedEvent;
import com.bugeon.fintechledger.transaction.service.TransactionAuditEventListener.IdempotentRequestEvent;
import com.bugeon.fintechledger.transaction.service.TransactionAuditEventListener.TransferCompletedEvent;
import com.bugeon.fintechledger.transaction.service.TransactionAuditEventListener.WithdrawalCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 거래 도메인 서비스.
 *
 * 금융 원칙:
 *   1. 돈은 절대 사라지거나 생성되지 않는다.
 *   2. 모든 거래는 단일 @Transactional로 보호된다.
 *   3. 잔액 조회/변경은 반드시 PESSIMISTIC_WRITE 락 후 수행한다.
 *   4. Transaction은 PENDING으로 시작, 모든 처리 완료 후 SUCCESS로 전환한다.
 *   5. 모든 거래는 LedgerEntry로 기록된다 (Double Entry).
 *   6. Currency는 계좌 통화와 반드시 일치해야 한다.
 *   7. DEPOSIT은 소유권 검증 없음 — 타인 계좌 입금 허용 (실제 금융 관행).
 *   8. Transfer 데드락 방지: 두 계좌를 항상 UUID ASC 순서로 락.
 *   9. 클라이언트 제공 Idempotency-Key로 중복 요청을 감지한다.
 *  10. 실패한 거래는 REQUIRES_NEW TX에서 FAILED 상태로 영구 저장된다.
 *  11. 성공 Audit은 AFTER_COMMIT 이벤트로 발행 — TX 롤백 시 Audit 오염 없음.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final AccountRepository          accountRepository;
    private final TransactionRepository      transactionRepository;
    private final LedgerService              ledgerService;
    private final AuditService               auditService;
    private final OutboxService              outboxService;
    private final TransactionFailureRecorder failureRecorder;
    private final ApplicationEventPublisher  eventPublisher;

    // ── DEPOSIT ───────────────────────────────────────────────────────────────

    /**
     * 입금 처리.
     *
     * 실행 순서 (모두 동일 TX):
     *   1. Idempotency 중복 확인
     *   2. Transaction PENDING 저장
     *   3. 계좌 락 + 상태/통화 검증
     *   4. 잔액 증가
     *   5. LedgerEntry CREDIT 저장
     *   6. Transaction SUCCESS 업데이트
     *   7. Outbox 이벤트 저장
     *   8. COMMIT
     *
     * TX 커밋 후:
     *   9. AFTER_COMMIT: Audit SUCCESS 기록
     *
     * 실패 시:
     *   catch → TransactionFailureRecorder(REQUIRES_NEW): FAILED 저장
     *   catch → AuditService(REQUIRES_NEW): FAILURE 기록
     *   main TX ROLLBACK
     */
    @Transactional
    public TransactionResponse deposit(String idempotencyKey,
                                       DepositRequest request,
                                       UserPrincipal principal) {

        // 1. Idempotency 중복 확인
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotent deposit: key={}", idempotencyKey);
            // AFTER_COMMIT: Audit IDEMPOTENT_REQUEST
            eventPublisher.publishEvent(new IdempotentRequestEvent(
                    existing.get().getId(), principal.getUserId(), principal.getEmail()));
            return TransactionResponse.from(existing.get());
        }

        // 2. Transaction PENDING → PROCESSING 저장
        // PROCESSING 상태로 즉시 전환하여 동시 재시도 요청이 "처리 중" 상태를 인지하게 함.
        // DB UNIQUE constraint가 1차 방어, PROCESSING 전환이 2차 상태 명확화.
        Transaction tx = Transaction.createDeposit(
                idempotencyKey, request.accountId(),
                request.amount(), request.currency(), request.description());
        transactionRepository.save(tx);
        tx.markProcessing();

        try {
            // 3. 계좌 락 + 검증 (C-3: 소유권 검증 없음)
            Account account = accountRepository.findByIdForUpdate(request.accountId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
            account.validateActive();
            validateCurrency(account, request.currency());

            BigDecimal amount      = request.amount();
            // 4. 잔액 증가
            account.increaseCachedBalance(amount);
            BigDecimal balanceAfter = account.getCachedBalance();

            // 5. LedgerEntry CREDIT
            ledgerService.recordDeposit(
                    tx.getId(), account.getId(),
                    amount, balanceAfter,
                    request.currency(), request.description());

            // 6. SUCCESS
            tx.markSuccess();

            // 7. Outbox (MANDATORY — 같은 TX)
            outboxService.publishDeposit(
                    tx.getId(), account.getId(),
                    amount.toPlainString(), request.currency());

            // 8. COMMIT 후 Audit은 AFTER_COMMIT 리스너에서 처리
            eventPublisher.publishEvent(new DepositCompletedEvent(
                    tx.getId(), account.getId(), amount,
                    principal.getUserId(), principal.getEmail()));

            log.info("Deposit SUCCESS: account={} amount={} balance={}",
                    account.getId(), amount, balanceAfter);
            return TransactionResponse.from(tx);

        } catch (Exception e) {
            // C-1: REQUIRED_NEW TX에서 FAILED 영구 저장 (main TX 롤백과 무관)
            failureRecorder.persistFailure(tx, e.getMessage());
            // REQUIRES_NEW: Audit FAILURE (main TX 롤백과 무관)
            auditService.logFailure(AuditAction.DEPOSIT_FAILED,
                    principal.getUserId(), principal.getEmail(),
                    "TRANSACTION", tx.getId(),
                    String.format("{\"reason\":\"%s\"}", sanitize(e.getMessage())));
            throw e;
        }
    }

    // ── WITHDRAW ──────────────────────────────────────────────────────────────

    /**
     * 출금 처리.
     *
     * 실행 순서:
     *   1. Idempotency 중복 확인
     *   2. Transaction PENDING 저장
     *   3. 계좌 락 + 소유권/상태/통화 검증
     *   4. 잔액 차감 (부족 시 INSUFFICIENT_FUNDS)
     *   5. LedgerEntry DEBIT 저장
     *   6. Transaction SUCCESS
     *   7. Outbox 이벤트 저장
     *   8. COMMIT → AFTER_COMMIT Audit
     */
    @Transactional
    public TransactionResponse withdraw(String idempotencyKey,
                                        WithdrawRequest request,
                                        UserPrincipal principal) {

        // 1. Idempotency
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            eventPublisher.publishEvent(new IdempotentRequestEvent(
                    existing.get().getId(), principal.getUserId(), principal.getEmail()));
            return TransactionResponse.from(existing.get());
        }

        // 2. PENDING → PROCESSING 저장
        Transaction tx = Transaction.createWithdrawal(
                idempotencyKey, request.accountId(),
                request.amount(), request.currency(), request.description());
        transactionRepository.save(tx);
        tx.markProcessing();

        try {
            // 3. 계좌 락 + 소유권/상태/통화 검증
            Account account = lockAndValidateOwner(request.accountId(), principal.getUserId());
            validateCurrency(account, request.currency());

            BigDecimal amount = request.amount();
            // 4. 잔액 차감
            account.decreaseCachedBalance(amount);
            BigDecimal balanceAfter = account.getCachedBalance();

            // 5. LedgerEntry DEBIT
            ledgerService.recordWithdrawal(
                    tx.getId(), account.getId(),
                    amount, balanceAfter,
                    request.currency(), request.description());

            // 6. SUCCESS
            tx.markSuccess();

            // 7. Outbox
            outboxService.publishWithdrawal(
                    tx.getId(), account.getId(),
                    amount.toPlainString(), request.currency());

            // 8. AFTER_COMMIT Audit
            eventPublisher.publishEvent(new WithdrawalCompletedEvent(
                    tx.getId(), account.getId(), amount,
                    principal.getUserId(), principal.getEmail()));

            log.info("Withdrawal SUCCESS: account={} amount={} balance={}",
                    account.getId(), amount, balanceAfter);
            return TransactionResponse.from(tx);

        } catch (Exception e) {
            failureRecorder.persistFailure(tx, e.getMessage());
            auditService.logFailure(AuditAction.WITHDRAWAL_FAILED,
                    principal.getUserId(), principal.getEmail(),
                    "TRANSACTION", tx.getId(),
                    String.format("{\"reason\":\"%s\",\"accountId\":\"%s\"}",
                            sanitize(e.getMessage()), request.accountId()));
            throw e;
        }
    }

    // ── TRANSFER ──────────────────────────────────────────────────────────────

    /**
     * 송금 처리 (Double Entry).
     *
     * 실행 순서:
     *   1. 자기 자신 송금 금지
     *   2. Idempotency 중복 확인
     *   3. Transaction PENDING 저장
     *   4. UUID ASC 순서로 두 계좌 PESSIMISTIC_WRITE 락 (데드락 방지)
     *   5. 소유권/상태/통화 검증
     *   6. fromAccount 잔액 차감
     *   7. toAccount 잔액 증가
     *   8. LedgerEntry DEBIT(from) + CREDIT(to) 저장
     *   9. Transaction SUCCESS
     *  10. Outbox 이벤트 저장
     *  11. COMMIT → AFTER_COMMIT Audit
     */
    @Transactional
    public TransactionResponse transfer(String idempotencyKey,
                                        TransferRequest request,
                                        UserPrincipal principal) {

        // 1. 자기 자신 송금 금지
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new BusinessException(ErrorCode.SELF_TRANSFER_NOT_ALLOWED);
        }

        // 2. Idempotency
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            eventPublisher.publishEvent(new IdempotentRequestEvent(
                    existing.get().getId(), principal.getUserId(), principal.getEmail()));
            return TransactionResponse.from(existing.get());
        }

        // 3. PENDING → PROCESSING 저장
        Transaction tx = Transaction.createTransfer(
                idempotencyKey, request.fromAccountId(), request.toAccountId(),
                request.amount(), request.currency(), request.description());
        transactionRepository.save(tx);
        tx.markProcessing();

        try {
            // 4. 두 계좌 UUID ASC 순서로 락 (데드락 방지)
            List<UUID> sortedIds = Arrays.asList(request.fromAccountId(), request.toAccountId());
            sortedIds.sort(UUID::compareTo);
            List<Account> locked = accountRepository.findAllByIdInForUpdate(sortedIds);

            if (locked.size() != 2) {
                throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND);
            }

            Account fromAccount = locked.stream()
                    .filter(a -> a.getId().equals(request.fromAccountId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
            Account toAccount = locked.stream()
                    .filter(a -> a.getId().equals(request.toAccountId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

            // 5. 검증 (toAccount 소유권은 검증 안 함 — 타인 계좌로 송금 가능)
            fromAccount.validateOwner(principal.getUserId());
            fromAccount.validateActive();
            toAccount.validateActive();
            validateCurrency(fromAccount, request.currency());
            validateCurrency(toAccount,   request.currency());

            BigDecimal amount = request.amount();

            // 6. fromAccount 잔액 차감
            fromAccount.decreaseCachedBalance(amount);
            BigDecimal fromBalanceAfter = fromAccount.getCachedBalance();

            // 7. toAccount 잔액 증가
            toAccount.increaseCachedBalance(amount);
            BigDecimal toBalanceAfter = toAccount.getCachedBalance();

            // 8. LedgerEntry Double Entry
            ledgerService.recordTransfer(
                    tx.getId(),
                    fromAccount.getId(), fromBalanceAfter,
                    toAccount.getId(),   toBalanceAfter,
                    amount, request.currency(), request.description());

            // 9. SUCCESS
            tx.markSuccess();

            // 10. Outbox
            outboxService.publishTransfer(
                    tx.getId(),
                    fromAccount.getId(), toAccount.getId(),
                    amount.toPlainString(), request.currency());

            // 11. AFTER_COMMIT Audit
            eventPublisher.publishEvent(new TransferCompletedEvent(
                    tx.getId(), fromAccount.getId(), toAccount.getId(), amount,
                    principal.getUserId(), principal.getEmail()));

            log.info("Transfer SUCCESS: from={} to={} amount={}",
                    fromAccount.getId(), toAccount.getId(), amount);
            return TransactionResponse.from(tx);

        } catch (Exception e) {
            failureRecorder.persistFailure(tx, e.getMessage());
            auditService.logFailure(AuditAction.TRANSFER_FAILED,
                    principal.getUserId(), principal.getEmail(),
                    "TRANSACTION", tx.getId(),
                    String.format("{\"reason\":\"%s\",\"from\":\"%s\",\"to\":\"%s\"}",
                            sanitize(e.getMessage()),
                            request.fromAccountId(), request.toAccountId()));
            throw e;
        }
    }

    // ── GET TRANSACTIONS ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactions(UUID accountId,
                                                     UserPrincipal principal,
                                                     Pageable pageable) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        account.validateOwner(principal.getUserId());

        return transactionRepository
                .findAllByAccountIds(List.of(accountId), pageable)
                .map(TransactionResponse::from);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * WITHDRAW 전용 계좌 락 + 소유권 + 활성 상태 검증.
     * DEPOSIT은 소유권 검증 없음 (C-3).
     */
    private Account lockAndValidateOwner(UUID accountId, UUID userId) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        account.validateOwner(userId);
        account.validateActive();
        return account;
    }

    /** 계좌 통화와 요청 통화가 일치하는지 검증 (H-2). */
    private void validateCurrency(Account account, String requestCurrency) {
        if (!account.getCurrency().equalsIgnoreCase(requestCurrency)) {
            throw new BusinessException(ErrorCode.CURRENCY_MISMATCH);
        }
    }

    /** 예외 메시지를 JSON 문자열에 안전하게 삽입할 수 있도록 이스케이프. */
    private String sanitize(String input) {
        if (input == null) return "";
        return input.replace("\"", "\\\"").replace("\n", " ");
    }
}
