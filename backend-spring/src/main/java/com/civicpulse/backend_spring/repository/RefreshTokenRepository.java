package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.RefreshToken;
import com.civicpulse.backend_spring.enums.RevocationReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByJti(String jti);

    /**
     * Revokes every still-live token in one session.
     *
     * The {@code revoked_at IS NULL} guard makes this idempotent and preserves
     * the original reason on rows already retired — a ROTATED row must not be
     * rewritten as LOGOUT when the rest of its chain is torn down.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken r
               SET r.revokedAt = :now, r.revokedReason = :reason
             WHERE r.sessionId = :sessionId
               AND r.revokedAt IS NULL
            """)
    int revokeSession(@Param("sessionId") String sessionId,
                      @Param("now") LocalDateTime now,
                      @Param("reason") RevocationReason reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken r
               SET r.revokedAt = :now, r.revokedReason = :reason
             WHERE r.userId = :userId
               AND r.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("now") LocalDateTime now,
                         @Param("reason") RevocationReason reason);

    /**
     * Sessions to drop from the in-memory access-token blocklist when signing
     * out everywhere. Distinct because one session is many chained rows.
     */
    @Query("""
            SELECT DISTINCT r.sessionId
              FROM RefreshToken r
             WHERE r.userId = :userId
               AND r.revokedAt IS NULL
            """)
    List<String> findActiveSessionIds(@Param("userId") Long userId);

    /**
     * Hard-deletes rows that are past expiry. Safe regardless of revocation
     * state: an expired token is refused by signature-and-expiry checking
     * before the table is ever consulted, so the row carries no meaning.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
