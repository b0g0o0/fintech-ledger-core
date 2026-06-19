package com.bugeon.fintechledger.transaction.service;

import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FAILED 상태의 Transaction을 영구적으로 저장하는 전용 서비스.
 *
 * 문제:
 *   TransactionService의 catch 블록에서 tx.markFailed()를 호출해도
 *   main @Transactional이 롤백되면 markFailed() 변경도 함께 롤백된다.
 *   결과: Audit에 "FAILED" 기록은 있지만 Transaction 테이블에는 레코드가 없어
 *   감사 추적이 불가능해진다.
 *
 * 해결:
 *   Propagation.REQUIRES_NEW로 별도 트랜잭션을 열어 FAILED 상태를 저장한다.
 *   main TX가 롤백되어도 이 저장은 독립적으로 커밋된다.
 *
 * 호출 시점:
 *   catch 블록에서 auditService.logFailure() 직전에 호출한다.
 *   AuditLog의 transactionId가 Transaction 테이블에 실제로 존재함을 보장한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionFailureRecorder {

    private final TransactionRepository transactionRepository;

    /**
     * FAILED 상태의 Transaction을 별도 트랜잭션으로 영구 저장.
     *
     * @param tx     이미 PENDING 상태로 저장된 Transaction 엔티티
     * @param reason 실패 사유 (failureReason 컬럼에 기록)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistFailure(Transaction tx, String reason) {
        try {
            tx.markFailed(reason);
            transactionRepository.save(tx);
            log.warn("Transaction FAILED persisted: id={} reason={}", tx.getId(), reason);
        } catch (Exception e) {
            // FAILED 저장 자체가 실패한 경우 — 최소한 로그는 남긴다
            // (예: TX가 아직 PENDING으로도 저장되지 않은 경우)
            log.error("Could not persist FAILED transaction: id={} reason={}", tx.getId(), reason, e);
        }
    }
}
