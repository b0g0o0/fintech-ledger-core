package com.bugeon.fintechledger.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox")
@Getter
@Setter
public class OutboxProperties {
    private long pollIntervalMs;
    private int  maxRetries;
}
