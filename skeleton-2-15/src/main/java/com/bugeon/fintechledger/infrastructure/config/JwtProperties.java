package com.bugeon.fintechledger.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds JWT configuration from {@code application.yml}:
 * <pre>
 * jwt:
 *   secret:           ${JWT_SECRET}       # HMAC-SHA256 key, ≥ 256 bits
 *   access-expiry-ms: 900000              # 15 min
 *   refresh-expiry-ms: 604800000          # 7 days
 * </pre>
 */
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /** HMAC-SHA256 signing secret — must be ≥ 32 UTF-8 characters (256 bits). */
    private String secret;

    /** Access token TTL in milliseconds. Default: 900 000 (15 minutes). */
    private long accessExpiryMs;

    /** Refresh token TTL in milliseconds. Default: 604 800 000 (7 days). */
    private long refreshExpiryMs;
}
