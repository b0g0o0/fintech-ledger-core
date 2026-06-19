package com.bugeon.fintechledger.transaction.repository;

import com.bugeon.fintechledger.transaction.domain.Transaction;
import com.bugeon.fintechledger.transaction.domain.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Full history page for a user: transactions where any of their accounts
     * appears as source or target.
     */
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.sourceAccountId IN :accountIds
           OR t.targetAccountId IN :accountIds
        ORDER BY t.createdAt DESC
        """)
    Page<Transaction> findAllByAccountIds(@Param("accountIds") List<UUID> accountIds,
                                          Pageable pageable);

    Page<Transaction> findAllBySourceAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    Page<Transaction> findAllByTargetAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    long countBySourceAccountIdAndStatus(UUID accountId, TransactionStatus status);
}
