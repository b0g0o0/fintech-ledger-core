package com.bugeon.fintechledger.account.service;

import com.bugeon.fintechledger.account.repository.AccountRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.*;

@DisplayName("AccountNumberGenerator")
@ExtendWith(MockitoExtension.class)
class AccountNumberGeneratorTest {

    @Mock AccountRepository      accountRepository;
    @InjectMocks AccountNumberGenerator generator;

    @BeforeEach
    void setUp() {
        given(accountRepository.existsByAccountNumber(anyString())).willReturn(false);
    }

    @Test @DisplayName("matches format 1001-0000-NNNNNN")
    void matchesFormat() {
        assertThat(generator.generate()).matches("1001-0000-\\d{6}");
    }

    @Test @DisplayName("has total length 16")
    void hasLength16() {
        assertThat(generator.generate()).hasSize(16);
    }

    @RepeatedTest(20) @DisplayName("always matches pattern")
    void alwaysMatchesPattern() {
        assertThat(generator.generate()).matches("1001-0000-\\d{6}");
    }

    @Test @DisplayName("retries on collision")
    void retriesOnCollision() {
        given(accountRepository.existsByAccountNumber(anyString()))
                .willReturn(true).willReturn(false);
        assertThat(generator.generate()).matches("1001-0000-\\d{6}");
        then(accountRepository).should(atLeast(2)).existsByAccountNumber(anyString());
    }

    @Test @DisplayName("throws IllegalStateException after MAX_RETRIES")
    void throwsAfterMaxRetries() {
        given(accountRepository.existsByAccountNumber(anyString())).willReturn(true);
        assertThatThrownBy(() -> generator.generate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique account number");
    }

    @Test @DisplayName("generates statistically distinct numbers")
    void generatesDistinctNumbers() {
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < 100; i++) numbers.add(generator.generate());
        assertThat(numbers.size()).isGreaterThan(90);
    }
}
