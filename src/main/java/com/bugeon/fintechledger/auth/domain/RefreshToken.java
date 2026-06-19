package com.bugeon.fintechledger.auth.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted refresh token record.
 *
 * Security model:
 *  - The <em>raw</em> token (a UUID string) is returned to the client once and never stored.
 *  - Only the BCrypt hash is persisted — a compromised database cannot yield valid tokens.
 *  - Single-use rotation: each call to {@code /auth/refresh} revokes the current token
 *    and issues a new one.  Reuse of a revoked token is an immediate revocation signal.
 *
 * Lookup strategy:
 *  To avoid scanning all tokens with BCrypt comparison, the client also submits the
 *  token itself; the repository can look it up by hash directly (token_hash column is
 *  UNIQUE and indexed).  BCrypt.matches() is only called once on the located row.
 */
@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
        @Index(name = "idx_rt_user_id",    columnList = "user_id"),
        @Index(name = "idx_rt_token_hash", columnList = "token_hash", unique = true)
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id")
@ToString(exclude = "tokenHash")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** FK to {@link User} — not mapped as an association to stay aggregate-independent. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** BCrypt hash of the raw opaque token sent to the client. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 255, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Set to {@code true} when the token is consumed (rotation) or user logs out. */
    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ── Factory ───────────────────────────────────────────────────────────────

    public static RefreshToken create(UUID userId, String tokenHash, Instant expiresAt) {
        RefreshToken rt  = new RefreshToken();
        rt.userId        = userId;
        rt.tokenHash     = tokenHash;
        rt.expiresAt     = expiresAt;
        rt.revoked       = false;
        rt.createdAt     = Instant.now();
        return rt;
    }

    // ── Domain behaviour ──────────────────────────────────────────────────────

    /** Marks the token as consumed.  Persisting this change is the caller's responsibility. */
    public void revoke() {
        this.revoked = true;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(this.expiresAt);
    }

    /** {@code true} iff the token has not been revoked and has not expired. */
    public boolean isValid() {
        return !revoked && !isExpired();
    }
}
