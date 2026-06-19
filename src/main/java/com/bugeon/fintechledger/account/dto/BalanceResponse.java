package com.bugeon.fintechledger.account.dto;

import com.bugeon.fintechledger.account.domain.Account;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceResponse(
        UUID       accountId,
        String     accountNumber,
        BigDecimal balance,
        String     currency
) {
    public static BalanceResponse from(Account account) {
        return new BalanceResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCachedBalance(),
                account.getCurrency()
        );
    }
}
