package com.bugeon.fintechledger.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 단일 계좌에 대한 정합성 검사 결과.
 *
 * cachedBalance: Account 테이블의 현재 잔액 (캐시)
 * ledgerBalance: LedgerEntry 합산 잔액 (권위적 원장 잔액)
 * discrepancy:   cachedBalance - ledgerBalance (0이면 정합)
 */
public record ReconciliationResult(
        UUID       accountId,
        BigDecimal cachedBalance,
        BigDecimal ledgerBalance,
        BigDecimal discrepancy,
        boolean    isMatch,
        Instant    checkedAt
) {
    public static ReconciliationResult of(UUID accountId,
                                          BigDecimal cachedBalance,
                                          BigDecimal ledgerBalance) {
        BigDecimal discrepancy = cachedBalance.subtract(ledgerBalance);
        boolean    isMatch     = discrepancy.compareTo(BigDecimal.ZERO) == 0;
        return new ReconciliationResult(
                accountId, cachedBalance, ledgerBalance, discrepancy, isMatch, Instant.now());
    }
}
