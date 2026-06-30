package com.bugeon.fintechledger.outbox.repository;

import com.bugeon.fintechledger.outbox.domain.EventStatus;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Fetches PENDING events due for processing.
     *
     * FOR UPDATE SKIP LOCKED:
     *  - Locks selected rows so no other instance processes them.
     *  - SKIP LOCKED skips rows already locked by a concurrent transaction,
     *    enabling safe parallel polling across multiple app instances.
     *
     * Must be called within an active @Transactional context.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT o FROM OutboxEvent o
        WHERE o.status = 'PENDING'
          AND o.scheduledAt <= :now
        ORDER BY o.scheduledAt ASC
        LIMIT :batchSize
        """)
    List<OutboxEvent> findPendingForProcessing(@Param("now") Instant now,
                                               @Param("batchSize") int batchSize);

    List<OutboxEvent> findAllByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
            String aggregateType, UUID aggregateId);

    long countByStatus(EventStatus status);

    /**
     * Finds events stuck in PROCESSING status beyond the timeout threshold.
     *
     * An event becomes stuck when the application crashes after markProcessing()
     * but before markSent(). Without this recovery, stuck events are lost forever
     * (At-Most-Once behavior). This query enables At-Least-Once by resetting
     * stale PROCESSING events back to PENDING for re-processing.
     *
     * staleThreshold = Instant.now() - processingTimeoutMinutes
     * Any event in PROCESSING state with scheduledAt before the threshold
     * is considered crashed/stuck and eligible for recovery.
     */
    @Query("""
        SELECT o FROM OutboxEvent o
        WHERE o.status = 'PROCESSING'
          AND o.scheduledAt < :staleThreshold
        ORDER BY o.scheduledAt ASC
        """)
    List<OutboxEvent> findStaleProcessingEvents(@Param("staleThreshold") Instant staleThreshold);
}
