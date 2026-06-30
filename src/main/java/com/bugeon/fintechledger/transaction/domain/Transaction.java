package com.bugeon.fintechledger.transaction.domain;

import com.bugeon.fintechledger.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money-movement command aggregate.
 *
 * Status FSM:
 *   PENDING ──► PROCESSING ──► SUCCESS
 *                           └──► FAILED
 *          └──► CANCELED
 *
 * Immutable after reaching a terminal state (SUCCESS, FAILED, CANCELED).
 * idempotency_key is the deduplication anchor — unique per request.
 * request_payload (JSONB) stores the original request for full auditability.
 */
@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id", callSuper = false)
@ToString
public class Transaction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "source_account_id", updatable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", updatable = false)
    private UUID targetAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload", columnDefinition = "jsonb")
    private String requestPayload;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ── Factories ─────────────────────────────────────────────────────────────

    public static Transaction createDeposit(String idempotencyKey,
                                            UUID targetAccountId,
                                            BigDecimal amount,
                                            String currency,
                                            String description) {
        return build(idempotencyKey, TransactionType.DEPOSIT,
                null, targetAccountId, amount, currency, description);
    }

    public static Transaction createWithdrawal(String idempotencyKey,
                                               UUID sourceAccountId,
                                               BigDecimal amount,
                                               String currency,
                                               String description) {
        return build(idempotencyKey, TransactionType.WITHDRAWAL,
                sourceAccountId, null, amount, currency, description);
    }

    public static Transaction createTransfer(String idempotencyKey,
                                             UUID sourceAccountId,
                                             UUID targetAccountId,
                                             BigDecimal amount,
                                             String currency,
                                             String description) {
        return build(idempotencyKey, TransactionType.TRANSFER,
                sourceAccountId, targetAccountId, amount, currency, description);
    }

    private static Transaction build(String idempotencyKey, TransactionType type,
                                     UUID sourceAccountId, UUID targetAccountId,
                                     BigDecimal amount, String currency, String description) {
        Transaction tx = new Transaction();
        tx.idempotencyKey  = idempotencyKey;
        tx.type            = type;
        tx.status          = TransactionStatus.PENDING;
        tx.sourceAccountId = sourceAccountId;
        tx.targetAccountId = targetAccountId;
        tx.amount          = amount;
        tx.currency        = currency;
        tx.description     = description;
        return tx;
    }

    // ── Status transitions ────────────────────────────────────────────────────

    public void markProcessing() {
        this.status = TransactionStatus.PROCESSING;
    }

    public void markSuccess() {
        this.status      = TransactionStatus.SUCCESS;
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status        = TransactionStatus.FAILED;
        this.failureReason = reason;
        this.completedAt   = Instant.now();
    }

    public void markCanceled() {
        this.status      = TransactionStatus.CANCELED;
        this.completedAt = Instant.now();
    }

    public boolean isTerminal() {
        return this.status == TransactionStatus.SUCCESS
            || this.status == TransactionStatus.FAILED
            || this.status == TransactionStatus.CANCELED;
    }

    public void attachRequestPayload(String payloadJson) {
        this.requestPayload = payloadJson;
    }
}
