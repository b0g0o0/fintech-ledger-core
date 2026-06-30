package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.infrastructure.config.OutboxProperties;
import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import com.bugeon.fintechledger.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional Outbox Processor.
 *
 * 동작 방식:
 *   1. PENDING OutboxEvent를 배치로 조회 (FOR UPDATE SKIP LOCKED).
 *   2. 각 이벤트를 개별 REQUIRES_NEW 트랜잭션에서 처리.
 *   3. 성공 시 SENT 커밋 → 실패 시 백오프 재스케줄 커밋.
 *
 * FIX M-1 — 이전 문제:
 *   process()가 단일 @Transactional이었으므로 하나의 이벤트가 RuntimeException을 던지면
 *   해당 배치의 모든 이벤트가 롤백되어 정상 처리된 이벤트도 PENDING으로 되돌아갔다.
 *   이로 인해 중복 발행 가능성이 있었다.
 *
 * 수정:
 *   processEvent()를 REQUIRES_NEW로 격리한다.
 *   하나의 이벤트 처리 실패가 나머지 이벤트에 영향을 주지 않는다.
 *   process() 자체는 @Transactional 없이 실행되어 각 이벤트의 락을 독립적으로 관리한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxProcessor {

    private static final int  BATCH_SIZE      = 100;
    private static final long BASE_BACKOFF_MS = 5_000L;
    private static final int  MAX_BACKOFF_EXP = 5;

    private final OutboxEventRepository    outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxProperties          outboxProperties;

    /**
     * 폴링 스케줄러 — @Transactional 없이 실행.
     * 개별 이벤트 처리는 processEvent()의 REQUIRES_NEW TX에서 처리.
     */
    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:5000}")
    public void process() {
        List<OutboxEvent> pending = fetchPending();
        if (pending.isEmpty()) return;

        log.debug("OutboxProcessor: processing {} events", pending.size());

        for (OutboxEvent event : pending) {
            try {
                processSingleEvent(event.getId());
            } catch (Exception e) {
                // 개별 이벤트 실패는 다음 이벤트 처리에 영향 없음
                log.error("OutboxProcessor: event {} failed in outer loop", event.getId(), e);
            }
        }
    }

    /**
     * PENDING 이벤트 목록 조회.
     * FOR UPDATE SKIP LOCKED: 다른 인스턴스가 처리 중인 행은 건너뜀.
     */
    @Transactional
    public List<OutboxEvent> fetchPending() {
        return outboxEventRepository.findPendingForProcessing(Instant.now(), BATCH_SIZE);
    }

    /**
     * 단일 이벤트를 독립 트랜잭션에서 처리.
     *
     * REQUIRES_NEW: 이 메서드의 트랜잭션은 호출자와 완전히 독립.
     * 성공 → SENT 상태로 커밋.
     * 실패 → 재시도 스케줄 커밋 (PENDING 상태 유지, scheduledAt 갱신).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingleEvent(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElse(null);

        // 다른 인스턴스가 이미 처리했을 수 있음
        if (event == null || event.getStatus().name().equals("SENT")) return;

        try {
            event.markProcessing();

            eventPublisher.publishEvent(new OutboxEventPublishedEvent(
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getEventType(),
                    event.getPayload()
            ));

            event.markSent();
            log.info("Outbox event published: type={} aggregateId={}",
                    event.getEventType(), event.getAggregateId());

        } catch (Exception e) {
            log.error("Outbox event failed: id={} type={} retry={}",
                    event.getId(), event.getEventType(), event.getRetryCount(), e);

            if (event.getRetryCount() >= outboxProperties.getMaxRetries()) {
                event.markPermanentlyFailed();
                log.error("Outbox event permanently failed: id={}", event.getId());
            } else {
                int exp = Math.min(event.getRetryCount(), MAX_BACKOFF_EXP);
                long backoffMs = BASE_BACKOFF_MS * (1L << exp);
                event.markFailedAndReschedule(Instant.now().plusMillis(backoffMs));
            }
        }
    }

    // ── Stale PROCESSING recovery ─────────────────────────────────────────────

    /**
     * 고착된 PROCESSING 이벤트를 PENDING으로 복구.
     *
     * 문제:
     *   processSingleEvent()에서 markProcessing() 호출 후 앱이 크래시되면
     *   해당 이벤트는 영구 PROCESSING 상태로 고착된다.
     *   findPendingForProcessing()은 PENDING만 조회하므로 재처리되지 않는다.
     *   → At-Most-Once: 이벤트 유실 발생
     *
     * 해결:
     *   5분 이상 PROCESSING 상태인 이벤트를 PENDING으로 리셋한다.
     *   다음 폴링 사이클에서 재처리된다. → At-Least-Once 보장.
     *
     * 실행 주기: 1분마다 (processingTimeout=5분과 독립적으로 실행)
     */
    @Scheduled(fixedDelay = 60_000L)
    @Transactional
    public void recoverStaleProcessingEvents() {
        // 5분 이상 PROCESSING 상태인 이벤트 = 크래시로 인한 고착으로 간주
        Instant staleThreshold = Instant.now().minusSeconds(300);
        List<OutboxEvent> stale = outboxEventRepository
                .findStaleProcessingEvents(staleThreshold);

        if (stale.isEmpty()) return;

        log.warn("Recovering {} stale PROCESSING event(s)", stale.size());

        for (OutboxEvent event : stale) {
            // scheduledAt을 now()로 리셋하여 즉시 재처리 대상이 되게 함
            event.markFailedAndReschedule(Instant.now());
            log.warn("Stale event reset to PENDING: id={} type={} retryCount={}",
                    event.getId(), event.getEventType(), event.getRetryCount());
        }
    }

    // ── Inner event class ─────────────────────────────────────────────────────

    public record OutboxEventPublishedEvent(
            String aggregateType,
            UUID   aggregateId,
            String eventType,
            String payload
    ) {}
}
