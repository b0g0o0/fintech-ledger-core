package com.bugeon.fintechledger.ledger.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable double-entry ledger record.
 *
 * Invariants enforced at service layer before persist:
 *  1. Every transaction produces ≥ 2 entries.
 *  2. SUM(signedAmount()) per transaction_id == 0  [double-entry balance].
 *  3. amount is always positive; sign is conveyed by entryType.
 *  4. runningBalance = previous running_balance ± amount.
 *
 * No UPDATE or DELETE is ever issued on this table.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id")
@ToString
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 6, updatable = false)
    private EntryType entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "running_balance", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal runningBalance;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "description", length = 255, updatable = false)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ── Factories ─────────────────────────────────────────────────────────────

    public static LedgerEntry credit(UUID transactionId, UUID accountId,
                                     BigDecimal amount, BigDecimal runningBalance,
                                     String currency, String description) {
        return create(transactionId, accountId, EntryType.CREDIT,
                amount, runningBalance, currency, description);
    }

    public static LedgerEntry debit(UUID transactionId, UUID accountId,
                                    BigDecimal amount, BigDecimal runningBalance,
                                    String currency, String description) {
        return create(transactionId, accountId, EntryType.DEBIT,
                amount, runningBalance, currency, description);
    }

    private static LedgerEntry create(UUID transactionId, UUID accountId,
                                      EntryType entryType,
                                      BigDecimal amount, BigDecimal runningBalance,
                                      String currency, String description) {
        LedgerEntry e = new LedgerEntry();
        e.transactionId  = transactionId;
        e.accountId      = accountId;
        e.entryType      = entryType;
        e.amount         = amount;
        e.runningBalance = runningBalance;
        e.currency       = currency;
        e.description    = description;
        e.createdAt      = Instant.now();
        return e;
    }

    // ── Domain logic ─────────────────────────────────────────────────────────

    /**
     * Signed amount for double-entry sum verification.
     * CREDIT = +amount, DEBIT = -amount.
     */
    public BigDecimal signedAmount() {
        return entryType == EntryType.CREDIT
               ? amount
               : amount.negate();
    }
}
