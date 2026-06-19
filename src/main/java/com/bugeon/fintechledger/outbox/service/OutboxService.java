package com.bugeon.fintechledger.outbox.service;

import com.bugeon.fintechledger.outbox.domain.OutboxEvent;
import com.bugeon.fintechledger.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Transactional Outbox Service.
 *
 * OutboxEvent는 반드시 비즈니스 TX와 동일한 트랜잭션에서 저장된다.
 * 이것이 Dual-Write 문제를 해결하는 핵심: DB COMMIT 후에야 이벤트가 발행됨.
 *
 * Propagation.MANDATORY: 반드시 호출자의 TX 내에서만 실행.
 * 만약 TX 없이 호출되면 즉시 IllegalTransactionStateException 발생 — 버그 조기 발견.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;

    /**
     * 입금 완료 이벤트 저장.
     * 호출자(TransactionService)의 TX와 동일한 트랜잭션에서 커밋.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishDeposit(UUID transactionId, UUID accountId,
                               String amount, String currency) {
        String payload = String.format(
                "{\"transactionId\":\"%s\",\"accountId\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}",
                transactionId, accountId, amount, currency);

        OutboxEvent event = OutboxEvent.create("TRANSACTION", transactionId,
                "MoneyDeposited", payload);
        outboxEventRepository.save(event);
        log.debug("Outbox event saved: MoneyDeposited txId={}", transactionId);
    }

    /**
     * 출금 완료 이벤트 저장.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishWithdrawal(UUID transactionId, UUID accountId,
                                  String amount, String currency) {
        String payload = String.format(
                "{\"transactionId\":\"%s\",\"accountId\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}",
                transactionId, accountId, amount, currency);

        OutboxEvent event = OutboxEvent.create("TRANSACTION", transactionId,
                "MoneyWithdrawn", payload);
        outboxEventRepository.save(event);
        log.debug("Outbox event saved: MoneyWithdrawn txId={}", transactionId);
    }

    /**
     * 송금 완료 이벤트 저장.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishTransfer(UUID transactionId, UUID fromAccountId,
                                UUID toAccountId, String amount, String currency) {
        String payload = String.format(
                "{\"transactionId\":\"%s\",\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\"}",
                transactionId, fromAccountId, toAccountId, amount, currency);

        OutboxEvent event = OutboxEvent.create("TRANSACTION", transactionId,
                "TransferCompleted", payload);
        outboxEventRepository.save(event);
        log.debug("Outbox event saved: TransferCompleted txId={}", transactionId);
    }
}
