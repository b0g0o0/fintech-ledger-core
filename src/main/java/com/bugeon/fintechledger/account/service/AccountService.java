package com.bugeon.fintechledger.account.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.dto.AccountResponse;
import com.bugeon.fintechledger.account.dto.BalanceResponse;
import com.bugeon.fintechledger.account.dto.CreateAccountRequest;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository      accountRepository;
    private final AccountNumberGenerator accountNumberGenerator;

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, UserPrincipal principal) {
        String accountNumber = accountNumberGenerator.generate();
        Account account = Account.create(
                principal.getUserId(), accountNumber,
                request.accountName(), request.currencyOrDefault());
        accountRepository.save(account);
        log.info("Account created: number={}", accountNumber);
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getMyAccounts(UserPrincipal principal) {
        return accountRepository
                .findAllByUserIdOrderByCreatedAtAsc(principal.getUserId())
                .stream().map(AccountResponse::from).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId, UserPrincipal principal) {
        return AccountResponse.from(load(accountId, principal.getUserId()));
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID accountId, UserPrincipal principal) {
        return BalanceResponse.from(load(accountId, principal.getUserId()));
    }

    private Account load(UUID accountId, UUID userId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        account.validateOwner(userId);
        return account;
    }
}
