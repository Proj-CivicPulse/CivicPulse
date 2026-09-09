package com.civicpulse.backend_spring.entity;

import com.civicpulse.backend_spring.enums.AuthTokenPurpose;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A single-use, emailed secret backing verification and password reset.
 *
 * {@code tokenHash} is SHA-256 of the value that was mailed; the value itself is
 * never persisted. See V10__email_verification_and_password_reset.sql for why.
 */
@Entity
@Table(
        name = "auth_tokens",
        indexes = {
                @Index(name = "idx_auth_tokens_user_purpose", columnList = "user_id, purpose"),
                @Index(name = "idx_auth_tokens_expires_at", columnList = "expires_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 24)
    private AuthTokenPurpose purpose;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Null until redeemed. Non-null is what makes the token single-use. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    public boolean isUsable(LocalDateTime now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }
}
