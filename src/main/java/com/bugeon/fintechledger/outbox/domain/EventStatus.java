package com.bugeon.fintechledger.outbox.domain;

public enum EventStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED
}
