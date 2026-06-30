package com.bugeon.fintechledger.reconciliation.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.ledger.repository.LedgerEntryRepository;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationReport;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reconciliation Service — 금융 데이터 무결성 검증.
 *
 * 설계 이유:
 *   Account.cachedBalance는 성능 최적화를 위한 캐시 값이다.
 *   권위적(Authoritative) 잔액은 LedgerEntry의 합산이다.
 *   PESSIMISTIC_WRITE 락과 동일 TX 내 업데이트로 두 값이 항상 일치해야 하지만,
 *   버그, 장애, 부분 실패 시 불일치가 발생할 수 있다.
 *   이를 자동으로 감지하여 즉시 대응 가능하게 한다.
 *
 * 실행 방식:
 *   - runFullReconciliation(): 모든 ACTIVE 계좌 대상 전체 검증
 *   - reconcileAccount(): 단일 계좌 즉시 검증 (의심 계좌 즉시 조사용)
 *
 * 불일치 시:
 *   1. RECONCILIATION_DISCREPANCY Audit 기록 (REQUIRES_NEW — 영구 보존)
 *   2. ERROR 레벨 로그 출력
 *   3. ReconciliationReport에 실패 목록 포함
 *
 * 성능 고려:
 *   대용량 계좌 처리를 위해 페이지 단위로 배치 처리.
 *   각 계좌는 readOnly TX에서 독립적으로 검사 (락 경합 없음).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private static final int PAGE_SIZE = 500;

    private final AccountRepository      accountRepository;
    private final LedgerEntryRepository  ledgerEntryRepository;
    private final AuditService           auditService;

    /**
     * 모든 계좌를 대상으로 전체 정합성 검증을 실행한다.
     * 페이지 단위 처리로 OOM 없이 대용량 처리 가능.
     *
     * @return ReconciliationReport 전체 실행 결과
     */
    @Transactional(readOnly = true)
    public ReconciliationReport runFullReconciliation() {
        Instant startedAt = Instant.now();
        log.info("Reconciliation started at {}", startedAt);

        List<ReconciliationResult> allResults = new ArrayList<>();
        int page = 0;

        while (true) {
            Page<Account> batch = accountRepository.findAll(PageRequest.of(page, PAGE_SIZE));
            if (batch.isEmpty()) break;

            for (Account account : batch.getContent()) {
                ReconciliationResult result = checkAccount(account);
                allResults.add(result);

                if (!result.isMatch()) {
                    handleDiscrepancy(result);
                }
            }

            if (batch.isLast()) break;
            page++;
        }

        ReconciliationReport report = ReconciliationReport.of(startedAt, allResults);

        log.info("Reconciliation completed: total={} matched={} discrepant={}",
                report.totalAccounts(), report.matchedAccounts(), report.discrepantAccounts());

        if (report.hasDiscrepancies()) {
            log.error("RECONCILIATION DISCREPANCIES DETECTED: {} account(s) out of balance",
                    report.discrepantAccounts());
        }

        return report;
    }

    /**
     * 단일 계좌에 대한 즉시 정합성 검증.
     * 의심 계좌 긴급 조사 또는 거래 완료 후 확인에 사용.
     *
     * @param accountId 검증할 계좌 ID
     * @return ReconciliationResult 검증 결과
     */
    @Transactional(readOnly = true)
    public ReconciliationResult reconcileAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        ReconciliationResult result = checkAccount(account);

        if (!result.isMatch()) {
            handleDiscrepancy(result);
        }

        return result;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * 단일 계좌의 cachedBalance와 LedgerEntry 합산을 비교한다.
     */
    private ReconciliationResult checkAccount(Account account) {
        BigDecimal cachedBalance = account.getCachedBalance();
        BigDecimal ledgerBalance = ledgerEntryRepository.calculateLedgerBalance(account.getId());

        return ReconciliationResult.of(account.getId(), cachedBalance, ledgerBalance);
    }

    /**
     * 불일치 발견 시 처리.
     * REQUIRES_NEW TX에서 Audit 기록 → main TX 롤백과 무관하게 영구 보존.
     */
    private void handleDiscrepancy(ReconciliationResult result) {
        log.error(
                "BALANCE DISCREPANCY: accountId={} cached={} ledger={} diff={}",
                result.accountId(),
                result.cachedBalance(),
                result.ledgerBalance(),
                result.discrepancy());

        String context = String.format(
                "{\"accountId\":\"%s\",\"cachedBalance\":\"%s\",\"ledgerBalance\":\"%s\",\"discrepancy\":\"%s\"}",
                result.accountId(),
                result.cachedBalance(),
                result.ledgerBalance(),
                result.discrepancy());

        // REQUIRES_NEW — Audit은 항상 기록되어야 함
        auditService.logFailure(
                AuditAction.RECONCILIATION_DISCREPANCY,
                null, null,
                "ACCOUNT", result.accountId(),
                context);
    }
}
