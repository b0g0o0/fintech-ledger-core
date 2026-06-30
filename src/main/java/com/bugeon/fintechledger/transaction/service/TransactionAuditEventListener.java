package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 거래 성공 Audit 이벤트 리스너.
 *
 * 문제:
 *   AuditService.logSuccess()를 main @Transactional 내에서 REQUIRES_NEW로 호출하면
 *   Audit이 먼저 별도 TX로 커밋된 후 main TX가 롤백되는 경우,
 *   Audit에 "SUCCESS"가 기록되지만 실제 잔액/Ledger/Transaction은 롤백된다.
 *
 * 해결:
 *   @TransactionalEventListener(phase = AFTER_COMMIT)을 사용하여
 *   main TX가 성공적으로 커밋된 후에만 Audit을 기록한다.
 *   main TX가 롤백되면 이 리스너는 실행되지 않는다.
 *
 * 주의:
 *   AFTER_COMMIT 리스너는 별도 TX에서 실행된다 (이미 REQUIRES_NEW처럼 동작).
 *   리스너 내부에서 발생하는 예외는 main TX와 무관하게 로그만 남긴다.
 *   Audit 실패가 비즈니스 로직을 방해해서는 안 된다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionAuditEventListener {

    private final AuditService auditService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDepositCompleted(DepositCompletedEvent event) {
        try {
            auditService.logSuccess(
                    AuditAction.DEPOSIT,
                    event.actorId(), event.actorEmail(),
                    "TRANSACTION", event.transactionId(),
                    buildContext("deposit", event.transactionId(),
                            event.accountId(), event.amount()));
        } catch (Exception e) {
            log.error("Audit logging failed for DEPOSIT txId={}", event.transactionId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWithdrawalCompleted(WithdrawalCompletedEvent event) {
        try {
            auditService.logSuccess(
                    AuditAction.WITHDRAWAL,
                    event.actorId(), event.actorEmail(),
                    "TRANSACTION", event.transactionId(),
                    buildContext("withdrawal", event.transactionId(),
                            event.accountId(), event.amount()));
        } catch (Exception e) {
            log.error("Audit logging failed for WITHDRAWAL txId={}", event.transactionId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransferCompleted(TransferCompletedEvent event) {
        try {
            auditService.logSuccess(
                    AuditAction.TRANSFER_COMPLETED,
                    event.actorId(), event.actorEmail(),
                    "TRANSACTION", event.transactionId(),
                    buildTransferContext(event.transactionId(),
                            event.fromAccountId(), event.toAccountId(), event.amount()));
        } catch (Exception e) {
            log.error("Audit logging failed for TRANSFER txId={}", event.transactionId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIdempotentRequest(IdempotentRequestEvent event) {
        try {
            auditService.logSuccess(
                    AuditAction.IDEMPOTENT_REQUEST,
                    event.actorId(), event.actorEmail(),
                    "TRANSACTION", event.transactionId(), null);
        } catch (Exception e) {
            log.error("Audit logging failed for IDEMPOTENT_REQUEST", e);
        }
    }

    // ── Domain Events ─────────────────────────────────────────────────────────

    public record DepositCompletedEvent(
            UUID transactionId, UUID accountId, BigDecimal amount,
            UUID actorId, String actorEmail) {}

    public record WithdrawalCompletedEvent(
            UUID transactionId, UUID accountId, BigDecimal amount,
            UUID actorId, String actorEmail) {}

    public record TransferCompletedEvent(
            UUID transactionId, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
            UUID actorId, String actorEmail) {}

    public record IdempotentRequestEvent(
            UUID transactionId, UUID actorId, String actorEmail) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildContext(String type, UUID txId, UUID accountId, BigDecimal amount) {
        return String.format(
                "{\"type\":\"%s\",\"transactionId\":\"%s\",\"accountId\":\"%s\",\"amount\":\"%s\"}",
                type, txId, accountId, amount);
    }

    private String buildTransferContext(UUID txId, UUID fromId, UUID toId, BigDecimal amount) {
        return String.format(
                "{\"type\":\"transfer\",\"transactionId\":\"%s\",\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"%s\"}",
                txId, fromId, toId, amount);
    }
}
