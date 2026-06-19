package com.bugeon.fintechledger.account.dto;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.domain.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID          accountId,
        String        accountNumber,
        String        accountName,
        String        currency,
        AccountStatus status,
        BigDecimal    balance,
        Instant       createdAt,
        Instant       updatedAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getAccountName(),
                account.getCurrency(),
                account.getStatus(),
                account.getCachedBalance(),
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
