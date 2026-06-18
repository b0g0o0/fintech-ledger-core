package com.bugeon.fintechledger.auth.repository;

import com.bugeon.fintechledger.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * O(1) lookup by the stored BCrypt hash.
     * The {@code token_hash} column has a UNIQUE index in the DB schema.
     * This is the primary lookup path for token validation.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Bulk-revokes all non-revoked tokens for a given user.
     * Called on logout to invalidate all active sessions for that user.
     *
     * @return number of rows updated
     */
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revoked = true " +
           "WHERE rt.userId = :userId AND rt.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);

    /**
     * Housekeeping — removes tokens that are either expired or already revoked.
     * Called by a nightly scheduled job to keep the table compact.
     *
     * @param cutoff rows with {@code expires_at < cutoff} are removed
     * @return number of rows deleted
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt " +
           "WHERE rt.expiresAt < :cutoff OR rt.revoked = true")
    int deleteExpiredAndRevoked(@Param("cutoff") Instant cutoff);
}
