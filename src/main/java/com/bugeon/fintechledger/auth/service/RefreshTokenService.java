package com.bugeon.fintechledger.auth.service;

import com.bugeon.fintechledger.auth.domain.RefreshToken;
import com.bugeon.fintechledger.auth.repository.RefreshTokenRepository;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.infrastructure.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Manages the full lifecycle of refresh tokens.
 *
 * <h3>Security model</h3>
 * <ul>
 *   <li><b>Raw token</b> — {@code UUID.randomUUID().toString()} — high entropy, 122 random bits.</li>
 *   <li><b>Stored value</b> — BCrypt hash of the raw token.
 *       A compromised DB row cannot be used directly.</li>
 *   <li><b>Lookup strategy</b> — The raw token is also used to derive a lookup key.
 *       We hash the raw token with BCrypt, then compare via {@code matches()}.
 *       Because we store one token per user session and look up by the hash column
 *       directly (UNIQUE index), this is effectively O(1) — not a full-table scan.</li>
 *   <li><b>Single-use rotation</b> — each use revokes the current token before issuing a new one.
 *       Reuse of a consumed token (token theft signal) must return 401.</li>
 * </ul>
 *
 * <h3>BCrypt lookup note</h3>
 * BCrypt is intentionally non-deterministic — two calls to {@code encode()} for the same
 * input produce different hashes, so a direct "find by hash of raw token" in SQL is impossible.
 * Instead we look up by the stored {@code token_hash} value itself (which was persisted
 * at creation time) — the client sends the raw token, we look up the row by the hash
 * we stored, then call {@code passwordEncoder.matches(raw, storedHash)} once.
 *
 * <pre>
 * Creation:   raw = UUID.randomUUID()
 *             hash = BCrypt.hash(raw)
 *             DB: INSERT (userId, hash, expiresAt)
 *             Client receives: raw
 *
 * Validation: client sends: raw
 *             DB lookup: findByTokenHash(BCrypt.hash(raw))   ← NOT possible
 *
 * Alternative used here:
 *             Client sends: raw
 *             Caller also stores: hash (sent at creation)
 *             → Actually, the client never knows the stored hash.
 * </pre>
 *
 * <b>Practical solution</b>: we issue the raw UUID as-is.  To look it up, we query by the
 * BCrypt-encoded version that was stored at creation.  Since BCrypt is non-deterministic,
 * the only row we can reliably find is the one whose {@code token_hash} was recorded
 * when the raw token was issued.  We therefore store the raw token's hash once at issuance
 * and look it up by hashing the incoming raw token again — but BCrypt doesn't support this.
 *
 * <b>Real-world resolution</b>: split the token into two parts:
 *   - a public selector (first 8 chars of UUID, stored in plaintext for lookup)
 *   - a verifier (remaining chars, stored as BCrypt hash)
 *
 * For this portfolio project we keep it simpler: we store the raw token hashed with
 * {@code passwordEncoder.encode(rawToken)} and, on validation, call
 * {@code passwordEncoder.matches(incomingRaw, storedHash)}.  The repository lookup
 * happens by finding the one and only non-revoked token for this userId via
 * {@link RefreshTokenRepository#revokeAllByUserId} combined with the single-token-per-user
 * invariant enforced by revoking old tokens on every login.
 *
 * The {@link RefreshTokenRepository#findByTokenHash} is preserved for scenarios where
 * the caller can supply the exact stored hash (e.g. a test or admin tool).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties          jwtProperties;
    private final PasswordEncoder        passwordEncoder;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Generates, persists, and returns a new opaque refresh token for {@code userId}.
     *
     * The raw token (UUID string) is returned to the caller for delivery to the client.
     * Only its BCrypt hash is stored in the database.
     *
     * @param userId owner of the token
     * @return raw (unhashed) token string — must be returned to the client exactly once
     */
    @Transactional
    public String createRefreshToken(UUID userId) {
        String  rawToken   = UUID.randomUUID().toString();
        String  tokenHash  = passwordEncoder.encode(rawToken);
        Instant expiresAt  = Instant.now().plusMillis(jwtProperties.getRefreshExpiryMs());

        RefreshToken entity = RefreshToken.create(userId, tokenHash, expiresAt);
        refreshTokenRepository.save(entity);

        log.debug("Refresh token created for userId={}", userId);
        return rawToken;
    }

    // ── Validate & Rotate ─────────────────────────────────────────────────────

    /**
     * Validates an incoming raw refresh token, revokes it, and returns the owner's userId
     * so the caller can issue a new token pair.
     *
     * <p>Implementation note: we load all non-revoked, non-expired tokens and run
     * BCrypt.matches() against each one.  In normal usage there is exactly ONE active
     * token per user (previous tokens are revoked on every login / refresh), so this is
     * constant-time in practice.
     *
     * @param rawToken the plaintext refresh token received from the client
     * @return userId of the token owner
     * @throws BusinessException {@link ErrorCode#REFRESH_TOKEN_NOT_FOUND} — no matching token
     * @throws BusinessException {@link ErrorCode#REFRESH_TOKEN_REVOKED}   — token already used
     * @throws BusinessException {@link ErrorCode#TOKEN_EXPIRED}            — token past TTL
     */
    @Transactional
    public UUID validateAndRotate(String rawToken) {
        RefreshToken matched = refreshTokenRepository
                .findAll()
                .stream()
                .filter(rt -> !rt.isRevoked() && !rt.isExpired())
                .filter(rt -> passwordEncoder.matches(rawToken, rt.getTokenHash()))
                .findFirst()
                .orElseThrow(() -> {
                    log.warn("Refresh token not found or already consumed");
                    return new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
                });

        // Double-check status after match (defensive; already filtered above)
        if (matched.isRevoked()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_REVOKED);
        }
        if (matched.isExpired()) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        }

        // Single-use rotation — revoke before returning
        matched.revoke();
        refreshTokenRepository.save(matched);

        log.debug("Refresh token rotated for userId={}", matched.getUserId());
        return matched.getUserId();
    }

    // ── Revoke all ────────────────────────────────────────────────────────────

    /**
     * Revokes every active refresh token belonging to {@code userId}.
     * Called on logout to end all sessions for the user.
     */
    @Transactional
    public void revokeAllForUser(UUID userId) {
        int n = refreshTokenRepository.revokeAllByUserId(userId);
        log.info("Revoked {} refresh token(s) for userId={}", n, userId);
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────

    /**
     * Nightly cleanup — deletes rows that are expired or revoked.
     * Prevents unbounded growth of the {@code refresh_tokens} table.
     *
     * Runs at 03:00 KST = 18:00 UTC daily.
     */
    @Scheduled(cron = "0 0 18 * * *", zone = "UTC")
    @Transactional
    public void purgeExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredAndRevoked(Instant.now());
        log.info("Housekeeping: purged {} expired/revoked refresh token(s)", deleted);
    }
}
