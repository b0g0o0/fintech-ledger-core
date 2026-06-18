package com.bugeon.fintechledger.account.domain;

import com.bugeon.fintechledger.common.domain.BaseEntity;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Virtual bank account aggregate.
 *
 * Balance rules:
 *  - cached_balance is a read-optimised projection; updated atomically
 *    within every locked transaction alongside the ledger write.
 *  - Authoritative balance = SUM(ledger_entries) — used for reconciliation.
 *  - All write paths MUST acquire a pessimistic lock via
 *    AccountRepository.findByIdForUpdate() before reading or mutating balance.
 *
 * @Version is an optimistic-lock fallback guard only.
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id", callSuper = false)
@ToString
public class Account extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_number", nullable = false, unique = true, length = 16, updatable = false)
    private String accountNumber;

    @Column(name = "account_name", nullable = false, length = 100)
    private String accountName;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status;

    @Column(name = "cached_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal cachedBalance;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ── Factory ──────────────────────────────────────────────────────────────

    public static Account create(UUID userId, String accountNumber,
                                 String accountName, String currency) {
        Account acc = new Account();
        acc.userId        = userId;
        acc.accountNumber = accountNumber;
        acc.accountName   = accountName;
        acc.currency      = currency;
        acc.status        = AccountStatus.ACTIVE;
        acc.cachedBalance = BigDecimal.ZERO;
        return acc;
    }

    // ── State guards ─────────────────────────────────────────────────────────

    public void validateActive() {
        if (this.status == AccountStatus.FROZEN) {
            throw new BusinessException(ErrorCode.ACCOUNT_FROZEN);
        }
        if (this.status == AccountStatus.CLOSED) {
            throw new BusinessException(ErrorCode.ACCOUNT_CLOSED);
        }
    }

    public void validateOwner(UUID requestingUserId) {
        if (!this.userId.equals(requestingUserId)) {
            throw new BusinessException(ErrorCode.ACCOUNT_ACCESS_DENIED);
        }
    }

    // ── Balance mutations (called only within a pessimistic-locked TX) ────────

    public void increaseCachedBalance(BigDecimal amount) {
        this.cachedBalance = this.cachedBalance.add(amount);
    }

    public void decreaseCachedBalance(BigDecimal amount) {
        BigDecimal result = this.cachedBalance.subtract(amount);
        if (result.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_FUNDS);
        }
        this.cachedBalance = result;
    }

    // ── Status transitions ────────────────────────────────────────────────────

    public void freeze() {
        this.status = AccountStatus.FROZEN;
    }

    public void close() {
        this.status = AccountStatus.CLOSED;
    }

    public void unfreeze() {
        this.status = AccountStatus.ACTIVE;
    }
}
