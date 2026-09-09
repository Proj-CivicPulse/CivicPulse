package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.entity.AuthToken;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.AuthTokenPurpose;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.AuthTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and redeems the single-use secrets mailed for email verification and
 * password reset.
 *
 * The raw token exists only in the response of {@link #issue} and in the email
 * built from it — what is stored is its SHA-256. See
 * V10__email_verification_and_password_reset.sql for why that matters more here
 * than almost anywhere else in the schema.
 */
@Service
@RequiredArgsConstructor
public class AuthTokenService {

    /**
     * 32 bytes from a CSPRNG — 256 bits, so guessing is not a threat model and
     * the token needs no rate limit of its own to be safe against brute force.
     * URL-safe and unpadded because it travels as a query parameter in a link.
     */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final AuthTokenRepository authTokenRepository;

    /**
     * Mints a token for one purpose and invalidates the user's outstanding ones.
     *
     * @return the RAW token — mail it, never store it
     */
    @Transactional
    public String issue(User user, AuthTokenPurpose purpose, Duration ttl) {
        LocalDateTime now = LocalDateTime.now();

        // Requesting a new link retires the previous one, so an older message
        // sitting in an inbox or a mail archive stops being a way in.
        authTokenRepository.consumeOutstanding(user.getId(), purpose, now);

        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = ENCODER.encodeToString(raw);

        authTokenRepository.save(AuthToken.builder()
                .tokenHash(hash(token))
                .purpose(purpose)
                .userId(user.getId())
                .createdAt(now)
                .expiresAt(now.plus(ttl))
                .build());

        return token;
    }

    /**
     * Redeems a token, marking it used so it cannot serve twice.
     *
     * @return the id of the user it belongs to
     * @throws ValidationException if it is unknown, expired, already used, or
     *         issued for a different purpose
     */
    @Transactional
    public Long consume(String token, AuthTokenPurpose purpose) {
        if (token == null || token.isBlank()) {
            throw invalid();
        }

        AuthToken stored = authTokenRepository.findByTokenHash(hash(token))
                .orElseThrow(AuthTokenService::invalid);

        LocalDateTime now = LocalDateTime.now();

        // Purpose is checked on redemption, not merely on issue: a verification
        // link is far easier to obtain than a reset link, and without this it
        // could be presented here to seize the account.
        if (stored.getPurpose() != purpose || !stored.isUsable(now)) {
            throw invalid();
        }

        stored.setConsumedAt(now);
        authTokenRepository.save(stored);

        return stored.getUserId();
    }

    /**
     * One message for every failure mode. Distinguishing "expired" from
     * "already used" from "never existed" would confirm to a stranger that a
     * token they hold was real, and buys the honest user nothing they cannot
     * fix by requesting another link.
     */
    private static ValidationException invalid() {
        return new ValidationException("That link is invalid or has expired. Request a new one.");
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the Java platform; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
