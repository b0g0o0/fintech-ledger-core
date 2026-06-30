package com.bugeon.fintechledger.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(

        @NotBlank(message = "Account name is required")
        @Size(min = 1, max = 100, message = "Account name must be between 1 and 100 characters")
        String accountName,

        @Pattern(regexp = "KRW|USD|EUR", message = "Currency must be KRW, USD, or EUR")
        String currency
) {
    public String currencyOrDefault() {
        return (currency != null && !currency.isBlank()) ? currency.toUpperCase() : "KRW";
    }
}
