package com.civicpulse.backend_spring.repository;

import com.civicpulse.backend_spring.entity.AuthToken;
import com.civicpulse.backend_spring.enums.AuthTokenPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    Optional<AuthToken> findByTokenHash(String tokenHash);

    /**
     * Retires a user's outstanding tokens of one purpose.
     *
     * Called when a new one is issued, so requesting a second reset link
     * invalidates the first: otherwise every link ever mailed would stay live
     * until expiry, and a single old message in an inbox — or a mail archive —
     * would still open the account.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AuthToken t
               SET t.consumedAt = :now
             WHERE t.userId = :userId
               AND t.purpose = :purpose
               AND t.consumedAt IS NULL
            """)
    int consumeOutstanding(@Param("userId") Long userId,
                           @Param("purpose") AuthTokenPurpose purpose,
                           @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM AuthToken t WHERE t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
