package com.bugeon.fintechledger.ledger.service;

import com.bugeon.fintechledger.ledger.domain.LedgerEntry;
import com.bugeon.fintechledger.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Double-Entry Ledger Service.
 *
 * 모든 금융 거래에 대해 불변(immutable) 원장 레코드를 생성한다.
 *
 * 원칙:
 *   - 입금(DEPOSIT):  CREDIT 1건 (잔액 증가)
 *   - 출금(WITHDRAW): DEBIT  1건 (잔액 감소)
 *   - 송금(TRANSFER): DEBIT  1건 + CREDIT 1건 = DEBIT + CREDIT 합계 = 0 (돈 보존)
 *
 * 반드시 호출자의 @Transactional 내에서 실행되어야 한다 (MANDATORY).
 * 원장 레코드는 생성 후 절대 수정/삭제되지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * 입금 원장 기록.
     * CREDIT 1건: 계좌 잔액 증가.
     *
     * @param transactionId 거래 ID
     * @param accountId     입금 계좌
     * @param amount        입금 금액
     * @param balanceAfter  입금 후 잔액
     * @param currency      통화
     * @param description   설명
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDeposit(UUID transactionId, UUID accountId,
                              BigDecimal amount, BigDecimal balanceAfter,
                              String currency, String description) {
        LedgerEntry credit = LedgerEntry.credit(
                transactionId, accountId, amount, balanceAfter, currency,
                "DEPOSIT: " + (description != null ? description : ""));
        ledgerEntryRepository.save(credit);

        log.debug("Ledger CREDIT recorded: account={} amount={} balance={}",
                accountId, amount, balanceAfter);
    }

    /**
     * 출금 원장 기록.
     * DEBIT 1건: 계좌 잔액 감소.
     *
     * @param transactionId 거래 ID
     * @param accountId     출금 계좌
     * @param amount        출금 금액
     * @param balanceAfter  출금 후 잔액
     * @param currency      통화
     * @param description   설명
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithdrawal(UUID transactionId, UUID accountId,
                                 BigDecimal amount, BigDecimal balanceAfter,
                                 String currency, String description) {
        LedgerEntry debit = LedgerEntry.debit(
                transactionId, accountId, amount, balanceAfter, currency,
                "WITHDRAWAL: " + (description != null ? description : ""));
        ledgerEntryRepository.save(debit);

        log.debug("Ledger DEBIT recorded: account={} amount={} balance={}",
                accountId, amount, balanceAfter);
    }

    /**
     * 송금 원장 기록 (Double Entry).
     * DEBIT 1건 (출금) + CREDIT 1건 (입금).
     * DEBIT.amount + CREDIT.amount = 0 → 돈은 절대 사라지거나 생성되지 않는다.
     *
     * @param transactionId       거래 ID
     * @param fromAccountId       출금 계좌
     * @param fromBalanceAfter    출금 후 잔액
     * @param toAccountId         입금 계좌
     * @param toBalanceAfter      입금 후 잔액
     * @param amount              이체 금액
     * @param currency            통화
     * @param description         설명
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTransfer(UUID transactionId,
                               UUID fromAccountId, BigDecimal fromBalanceAfter,
                               UUID toAccountId,   BigDecimal toBalanceAfter,
                               BigDecimal amount,  String currency, String description) {

        String desc = description != null ? description : "";

        // DEBIT: 송금인 계좌에서 차감
        LedgerEntry debit = LedgerEntry.debit(
                transactionId, fromAccountId, amount, fromBalanceAfter, currency,
                "TRANSFER OUT: " + desc);

        // CREDIT: 수신인 계좌에 추가
        LedgerEntry credit = LedgerEntry.credit(
                transactionId, toAccountId, amount, toBalanceAfter, currency,
                "TRANSFER IN: " + desc);

        ledgerEntryRepository.save(debit);
        ledgerEntryRepository.save(credit);

        log.debug("Ledger TRANSFER recorded: from={} to={} amount={}",
                fromAccountId, toAccountId, amount);
    }
}
