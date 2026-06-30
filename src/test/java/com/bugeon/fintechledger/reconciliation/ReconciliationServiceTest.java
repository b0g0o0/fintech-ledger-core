package com.bugeon.fintechledger.reconciliation;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.audit.domain.AuditAction;
import com.bugeon.fintechledger.audit.service.AuditService;
import com.bugeon.fintechledger.ledger.repository.LedgerEntryRepository;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationReport;
import com.bugeon.fintechledger.reconciliation.domain.ReconciliationResult;
import com.bugeon.fintechledger.reconciliation.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@DisplayName("ReconciliationService")
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock AccountRepository     accountRepository;
    @Mock LedgerEntryRepository ledgerEntryRepository;
    @Mock AuditService          auditService;

    @InjectMocks ReconciliationService reconciliationService;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
    }

    // ── runFullReconciliation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("runFullReconciliation")
    class RunFullReconciliation {

        @Test
        @DisplayName("모든 계좌 잔액이 Ledger와 일치하면 — hasDiscrepancies=false")
        void allMatch_noDiscrepancies() {
            Account account = accountWithBalance(accountId, new BigDecimal("50000"));
            given(accountRepository.findAll(any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(account)));
            given(ledgerEntryRepository.calculateLedgerBalance(accountId))
                    .willReturn(new BigDecimal("50000"));

            ReconciliationReport report = reconciliationService.runFullReconciliation();

            assertThat(report.hasDiscrepancies()).isFalse();
            assertThat(report.totalAccounts()).isEqualTo(1);
            assertThat(report.matchedAccounts()).isEqualTo(1);
            assertThat(report.discrepantAccounts()).isEqualTo(0);
            assertThat(report.failures()).isEmpty();

            // Audit 기록 없어야 함
            then(auditService).should(never()).logFailure(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("계좌 잔액과 Ledger 불일치 — Audit DISCREPANCY 기록 + report에 포함")
        void discrepancyDetected_auditRecorded() {
            Account account = accountWithBalance(accountId, new BigDecimal("50000"));
            given(accountRepository.findAll(any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(account)));
            // Ledger 합산이 다름 (불일치)
            given(ledgerEntryRepository.calculateLedgerBalance(accountId))
                    .willReturn(new BigDecimal("45000"));

            ReconciliationReport report = reconciliationService.runFullReconciliation();

            assertThat(report.hasDiscrepancies()).isTrue();
            assertThat(report.discrepantAccounts()).isEqualTo(1);
            assertThat(report.failures()).hasSize(1);

            ReconciliationResult failure = report.failures().get(0);
            assertThat(failure.accountId()).isEqualTo(accountId);
            assertThat(failure.cachedBalance()).isEqualByComparingTo("50000");
            assertThat(failure.ledgerBalance()).isEqualByComparingTo("45000");
            assertThat(failure.discrepancy()).isEqualByComparingTo("5000");
            assertThat(failure.isMatch()).isFalse();

            // Audit RECONCILIATION_DISCREPANCY 기록
            then(auditService).should().logFailure(
                    eq(AuditAction.RECONCILIATION_DISCREPANCY),
                    isNull(), isNull(),
                    eq("ACCOUNT"), eq(accountId), any());
        }

        @Test
        @DisplayName("계좌 없음 — 빈 보고서 반환")
        void noAccounts_emptyReport() {
            given(accountRepository.findAll(any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            ReconciliationReport report = reconciliationService.runFullReconciliation();

            assertThat(report.totalAccounts()).isEqualTo(0);
            assertThat(report.hasDiscrepancies()).isFalse();
        }

        @Test
        @DisplayName("여러 계좌 중 일부만 불일치 — 불일치 계좌만 report에 포함")
        void partialDiscrepancy_onlyFailuresInReport() {
            UUID matchId      = UUID.randomUUID();
            UUID mismatchId   = UUID.randomUUID();
            Account matchAcc  = accountWithBalance(matchId,    new BigDecimal("10000"));
            Account mismatch  = accountWithBalance(mismatchId, new BigDecimal("20000"));

            given(accountRepository.findAll(any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(matchAcc, mismatch)));
            given(ledgerEntryRepository.calculateLedgerBalance(matchId))
                    .willReturn(new BigDecimal("10000"));   // 일치
            given(ledgerEntryRepository.calculateLedgerBalance(mismatchId))
                    .willReturn(new BigDecimal("19999"));   // 불일치 (1원 차이)

            ReconciliationReport report = reconciliationService.runFullReconciliation();

            assertThat(report.totalAccounts()).isEqualTo(2);
            assertThat(report.matchedAccounts()).isEqualTo(1);
            assertThat(report.discrepantAccounts()).isEqualTo(1);
            assertThat(report.failures().get(0).accountId()).isEqualTo(mismatchId);
            assertThat(report.failures().get(0).discrepancy()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("Ledger 잔액이 0 (거래 없는 신규 계좌) — cachedBalance도 0이면 일치")
        void newAccountZeroBalance_matches() {
            Account account = accountWithBalance(accountId, BigDecimal.ZERO);
            given(accountRepository.findAll(any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(account)));
            given(ledgerEntryRepository.calculateLedgerBalance(accountId))
                    .willReturn(BigDecimal.ZERO);

            ReconciliationReport report = reconciliationService.runFullReconciliation();

            assertThat(report.hasDiscrepancies()).isFalse();
        }
    }

    // ── reconcileAccount ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("reconcileAccount")
    class ReconcileAccount {

        @Test
        @DisplayName("단일 계좌 일치 — isMatch=true, Audit 없음")
        void singleAccount_match() {
            Account account = accountWithBalance(accountId, new BigDecimal("30000"));
            given(accountRepository.findById(accountId)).willReturn(Optional.of(account));
            given(ledgerEntryRepository.calculateLedgerBalance(accountId))
                    .willReturn(new BigDecimal("30000"));

            ReconciliationResult result = reconciliationService.reconcileAccount(accountId);

            assertThat(result.isMatch()).isTrue();
            assertThat(result.discrepancy()).isEqualByComparingTo("0");
            then(auditService).should(never()).logFailure(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("단일 계좌 불일치 — isMatch=false, Audit DISCREPANCY 기록")
        void singleAccount_mismatch() {
            Account account = accountWithBalance(accountId, new BigDecimal("30000"));
            given(accountRepository.findById(accountId)).willReturn(Optional.of(account));
            given(ledgerEntryRepository.calculateLedgerBalance(accountId))
                    .willReturn(new BigDecimal("25000"));   // 5000 불일치

            ReconciliationResult result = reconciliationService.reconcileAccount(accountId);

            assertThat(result.isMatch()).isFalse();
            assertThat(result.discrepancy()).isEqualByComparingTo("5000");
            then(auditService).should().logFailure(
                    eq(AuditAction.RECONCILIATION_DISCREPANCY),
                    any(), any(), any(), eq(accountId), any());
        }

        @Test
        @DisplayName("존재하지 않는 계좌 — IllegalArgumentException")
        void accountNotFound_throws() {
            given(accountRepository.findById(accountId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reconciliationService.reconcileAccount(accountId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Account not found");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Account accountWithBalance(UUID id, BigDecimal balance) {
        Account account = Account.create(UUID.randomUUID(), "1001-0000-000001", "Test", "KRW");
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
