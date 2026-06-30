package com.bugeon.fintechledger.ledger.repository;

import com.bugeon.fintechledger.ledger.domain.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findAllByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    Page<LedgerEntry> findAllByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    /**
     * Authoritative balance derived from the ledger.
     * CREDIT = +amount, DEBIT = -amount.
     * Used by ReconciliationService to compare against Account.cachedBalance.
     */
    @Query("""
        SELECT COALESCE(
            SUM(CASE WHEN le.entryType = 'CREDIT' THEN le.amount ELSE -le.amount END),
            0
        )
        FROM LedgerEntry le
        WHERE le.accountId = :accountId
        """)
    BigDecimal calculateLedgerBalance(@Param("accountId") UUID accountId);

    /**
     * Returns the latest running_balance for an account.
     * Used when computing runningBalance for the next entry to insert.
     */
    @Query("""
        SELECT le.runningBalance
        FROM LedgerEntry le
        WHERE le.accountId = :accountId
        ORDER BY le.createdAt DESC
        LIMIT 1
        """)
    Optional<BigDecimal> findLatestRunningBalance(@Param("accountId") UUID accountId);
}
