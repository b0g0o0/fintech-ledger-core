package com.bugeon.fintechledger.account.repository;

import com.bugeon.fintechledger.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    /**
     * Acquires a DB-level exclusive lock (SELECT ... FOR UPDATE).
     * MUST be called within an active @Transactional context.
     * Required before any balance read or mutation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Locks multiple accounts in deterministic ascending-UUID order.
     * This prevents deadlocks on concurrent transfers between the same pair.
     * Always use this for transfer operations — never lock in arbitrary order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
    List<Account> findAllByIdInForUpdate(@Param("ids") List<UUID> ids);
}
