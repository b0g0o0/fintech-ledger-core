package com.bugeon.fintechledger.account.service;

import com.bugeon.fintechledger.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates unique account numbers in format: 1001-0000-NNNNNN
 * Total length = 16 chars (4 + 1 + 4 + 1 + 6).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountNumberGenerator {

    private static final String BANK_CODE    = "1001";
    private static final String PRODUCT_CODE = "0000";
    private static final int    SEQ_DIGITS   = 6;
    private static final int    MAX_RETRIES  = 10;

    private final AccountRepository accountRepository;
    private final SecureRandom      random = new SecureRandom();

    public String generate() {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            String candidate = BANK_CODE + "-" + PRODUCT_CODE + "-"
                    + String.format("%0" + SEQ_DIGITS + "d", random.nextInt(1_000_000));
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Failed to generate a unique account number after " + MAX_RETRIES + " attempts");
    }
}
