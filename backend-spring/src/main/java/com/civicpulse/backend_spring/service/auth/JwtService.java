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
 * Both also carry {@code sid}, the login session they belong to. That is what
 * makes revocation possible on either token type: refresh tokens are checked
 * against the {@code refresh_tokens} table (see {@link RefreshTokenService}),
 * and access tokens against {@link SessionRevocationRegistry}. Refresh tokens
 * additionally carry a {@code jti} identifying the individual token, so a
 * rotated one can be recognised if it is ever replayed.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_SESSION = "sid";

    private final JwtProperties jwtProperties;

    private SecretKey getSecretKey() {
        // Length is enforced by @Size on JwtProperties.secret, so this can
        // no longer fail with a WeakKeyException at request time.
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    private String generate(Long userId, String role, String type, String sessionId,
                            String jti, long ttlMillis) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_SESSION, sessionId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis));

        if (jti != null) {
            builder.id(jti);
        }
        return builder.signWith(getSecretKey()).compact();
    }

    public String generateAccessToken(Long userId, String role, String sessionId) {
        // No jti: access tokens are never looked up individually — they are
        // revoked by session, and they expire too fast to be worth a row.
        return generate(userId, role, TYPE_ACCESS, sessionId, null,
                jwtProperties.getAccessExpiration());
    }

    public String generateRefreshToken(Long userId, String role, String sessionId, String jti) {
        return generate(userId, role, TYPE_REFRESH, sessionId, jti,
                jwtProperties.getRefreshExpiration());
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

    /** Null for a token minted before sessions existed — callers must reject those. */
    public String extractSessionId(Claims claims) {
        return claims.get(CLAIM_SESSION, String.class);
    }

    /** The individual refresh token's id. Null on access tokens. */
    public String extractTokenId(Claims claims) {
        return claims.getId();
    }
}
