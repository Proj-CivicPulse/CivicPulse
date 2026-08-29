package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies the two token types.
 *
 * Both are signed JWTs carrying a {@code typ} claim so a refresh token can
 * never be replayed as an access token (or vice versa) — without that
 * claim, the long-lived refresh token would authenticate any request.
 *
 * Tokens are stateless: there is no server-side revocation list yet, so a
 * stolen refresh token stays valid until it expires. Adding a
 * {@code refresh_tokens} table (jti + revoked_at) is the follow-up if the
 * team wants real logout-everywhere semantics.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_ROLE = "role";

    private final JwtProperties jwtProperties;

    private SecretKey getSecretKey() {
        // Length is enforced by @Size on JwtProperties.secret, so this can
        // no longer fail with a WeakKeyException at request time.
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    private String generate(Long userId, String role, String type, long ttlMillis) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(getSecretKey())
                .compact();
    }

    public String generateAccessToken(Long userId, String role) {
        return generate(userId, role, TYPE_ACCESS, jwtProperties.getAccessExpiration());
    }

    public String generateRefreshToken(Long userId, String role) {
        return generate(userId, role, TYPE_REFRESH, jwtProperties.getRefreshExpiration());
    }

    /**
     * Verifies signature and expiry, then checks the token is of the
     * expected type. Returns empty rather than throwing — callers treat any
     * unusable token the same way, and an invalid token is an expected
     * condition, not an exceptional one.
     */
    public Optional<Claims> parse(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSecretKey())
                    .build()
                    .parseSignedClaims(token)   // throws if signature or expiry is bad
                    .getPayload();

            if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public Long extractUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public String extractRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }
}
