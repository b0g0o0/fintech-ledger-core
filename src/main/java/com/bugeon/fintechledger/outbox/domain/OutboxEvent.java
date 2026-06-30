package com.bugeon.fintechledger.outbox.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox event.
 *
 * Written within the SAME DB transaction as domain changes — eliminates
 * the dual-write problem and guarantees at-least-once event delivery.
 *
 * OutboxProcessor polls PENDING rows with FOR UPDATE SKIP LOCKED,
 * preventing concurrent processing across multiple app instances.
 *
 * Future: replace in-process event publishing with Kafka producer
 * in OutboxProcessor without any schema or entity changes.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id")
@ToString
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Domain object type — e.g. "TRANSACTION", "ACCOUNT", "USER" */
    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    /** Event class name — e.g. "TransferCompleted", "MoneyDeposited" */
    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EventStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ── Factory ──────────────────────────────────────────────────────────────

    public static OutboxEvent create(String aggregateType, UUID aggregateId,
                                     String eventType, String payloadJson) {
        OutboxEvent e = new OutboxEvent();
        e.aggregateType = aggregateType;
        e.aggregateId   = aggregateId;
        e.eventType     = eventType;
        e.payload       = payloadJson;
        e.status        = EventStatus.PENDING;
        e.retryCount    = 0;
        e.scheduledAt   = Instant.now();
        e.createdAt     = Instant.now();
        return e;
    }

    // ── State transitions ─────────────────────────────────────────────────────

    public void markProcessing() {
        this.status = EventStatus.PROCESSING;
    }

    public void markSent() {
        this.status      = EventStatus.SENT;
        this.processedAt = Instant.now();
    }

    public void markFailedAndReschedule(Instant nextAttempt) {
        this.retryCount++;
        this.status      = EventStatus.PENDING;
        this.scheduledAt = nextAttempt;
    }

    public void markPermanentlyFailed() {
        this.retryCount++;
        this.status = EventStatus.FAILED;
    }
}
