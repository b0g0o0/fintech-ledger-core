package com.bugeon.fintechledger.ledger.domain;

public enum EntryType {
    /**
     * CREDIT: money flowing IN to the account. Increases balance.
     * Used for: deposit target, transfer target.
     */
    CREDIT,

    /**
     * DEBIT: money flowing OUT of the account. Decreases balance.
     * Used for: withdrawal source, transfer source.
     */
    DEBIT
}
