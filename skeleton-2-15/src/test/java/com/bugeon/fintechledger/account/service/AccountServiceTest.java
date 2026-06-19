package com.bugeon.fintechledger.account.service;

import com.bugeon.fintechledger.account.domain.Account;
import com.bugeon.fintechledger.account.domain.AccountStatus;
import com.bugeon.fintechledger.account.dto.AccountResponse;
import com.bugeon.fintechledger.account.dto.BalanceResponse;
import com.bugeon.fintechledger.account.dto.CreateAccountRequest;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@DisplayName("AccountService")
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository      accountRepository;
    @Mock AccountNumberGenerator accountNumberGenerator;
    @InjectMocks AccountService  accountService;

    private UserPrincipal principal;
    private UUID          userId;

    @BeforeEach
    void setUp() {
        userId    = UUID.randomUUID();
        principal = UserPrincipal.of(userId, "user@example.com");
    }

    @Nested @DisplayName("createAccount")
    class CreateAccount {

        @Test @DisplayName("creates ACTIVE account with zero balance")
        void createsActive() {
            given(accountNumberGenerator.generate()).willReturn("1001-0000-000001");
            given(accountRepository.save(any())).willAnswer(i -> i.getArgument(0));

            AccountResponse r = accountService.createAccount(
                    new CreateAccountRequest("Savings", "KRW"), principal);

            assertThat(r.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(r.balance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(r.currency()).isEqualTo("KRW");
        }

        @Test @DisplayName("defaults currency to KRW when null")
        void defaultsToKrw() {
            given(accountNumberGenerator.generate()).willReturn("1001-0000-000002");
            given(accountRepository.save(any())).willAnswer(i -> i.getArgument(0));

            assertThat(accountService.createAccount(
                    new CreateAccountRequest("S", null), principal).currency()).isEqualTo("KRW");
        }

        @Test @DisplayName("multiple accounts get distinct numbers")
        void distinctNumbers() {
            given(accountNumberGenerator.generate())
                    .willReturn("1001-0000-000001").willReturn("1001-0000-000002");
            given(accountRepository.save(any())).willAnswer(i -> i.getArgument(0));

            AccountResponse a = accountService.createAccount(new CreateAccountRequest("A","KRW"), principal);
            AccountResponse b = accountService.createAccount(new CreateAccountRequest("B","KRW"), principal);
            assertThat(a.accountNumber()).isNotEqualTo(b.accountNumber());
        }
    }

    @Nested @DisplayName("getMyAccounts")
    class GetMyAccounts {

        @Test @DisplayName("returns all accounts for user")
        void returnsAll() {
            given(accountRepository.findAllByUserIdOrderByCreatedAtAsc(userId))
                    .willReturn(List.of(Account.create(userId,"1001-0000-000001","S","KRW")));
            assertThat(accountService.getMyAccounts(principal)).hasSize(1);
        }

        @Test @DisplayName("returns empty list when no accounts")
        void returnsEmpty() {
            given(accountRepository.findAllByUserIdOrderByCreatedAtAsc(userId)).willReturn(List.of());
            assertThat(accountService.getMyAccounts(principal)).isEmpty();
        }
    }

    @Nested @DisplayName("getAccount")
    class GetAccount {

        @Test @DisplayName("returns account for owner")
        void returnsForOwner() {
            UUID id = UUID.randomUUID();
            given(accountRepository.findById(id))
                    .willReturn(Optional.of(Account.create(userId,"1001-0000-000001","S","KRW")));

            assertThat(accountService.getAccount(id, principal).accountNumber())
                    .isEqualTo("1001-0000-000001");
        }

        @Test @DisplayName("throws ACCOUNT_NOT_FOUND")
        void throwsNotFound() {
            UUID id = UUID.randomUUID();
            given(accountRepository.findById(id)).willReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.getAccount(id, principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        }

        @Test @DisplayName("throws ACCOUNT_ACCESS_DENIED for non-owner")
        void throwsAccessDenied() {
            UUID id = UUID.randomUUID();
            given(accountRepository.findById(id))
                    .willReturn(Optional.of(Account.create(UUID.randomUUID(),"1001-0000-000099","O","KRW")));

            assertThatThrownBy(() -> accountService.getAccount(id, principal))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.ACCOUNT_ACCESS_DENIED);
        }
    }

    @Nested @DisplayName("getBalance")
    class GetBalance {

        @Test @DisplayName("returns zero balance for new account")
        void returnsZeroBalance() {
            UUID id = UUID.randomUUID();
            given(accountRepository.findById(id))
                    .willReturn(Optional.of(Account.create(userId,"1001-0000-000001","S","KRW")));

            BalanceResponse r = accountService.getBalance(id, principal);
            assertThat(r.balance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(r.currency()).isEqualTo("KRW");
        }
    }
}
