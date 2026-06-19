package com.bugeon.fintechledger.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "idempotency")
@Getter
@Setter
public class IdempotencyProperties {
    private long ttlSeconds;
    private long processingTtlSeconds;
}
