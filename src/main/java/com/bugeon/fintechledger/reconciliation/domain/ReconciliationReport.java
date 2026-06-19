package com.bugeon.fintechledger.reconciliation.domain;

import java.time.Instant;
import java.util.List;

/**
 * 단일 정합성 검사 실행의 전체 보고서.
 *
 * totalAccounts:     검사한 계좌 수
 * matchedAccounts:   정합인 계좌 수
 * discrepantAccounts: 불일치 계좌 수
 * failures:          불일치 상세 목록
 */
public record ReconciliationReport(
        Instant                    startedAt,
        Instant                    completedAt,
        int                        totalAccounts,
        int                        matchedAccounts,
        int                        discrepantAccounts,
        List<ReconciliationResult> failures
) {
    public boolean hasDiscrepancies() {
        return discrepantAccounts > 0;
    }

    public static ReconciliationReport of(Instant startedAt,
                                          List<ReconciliationResult> allResults) {
        List<ReconciliationResult> failures = allResults.stream()
                .filter(r -> !r.isMatch())
                .toList();
        return new ReconciliationReport(
                startedAt,
                Instant.now(),
                allResults.size(),
                allResults.size() - failures.size(),
                failures.size(),
                failures
        );
    }
}
