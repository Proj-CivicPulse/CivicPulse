package com.civicpulse.backend_spring.service.photo;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.config.JwtProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * The proof that lets whoever just filed a report attach its photo without an
 * account (docs/photo-upload-contract.md §5).
 *
 * <p>The contract named the reference number as that proof. It is not one:
 * reference numbers are sequential ({@code CP-2026-W41-00194}), so the next one
 * is guessable, and anyone could attach a photo to a stranger's report. This
 * token is an HMAC over the complaint id and an expiry, returned once on the
 * POST /complaints response — stateless, unguessable, bound to one complaint,
 * and short-lived.
 *
 * <p>Format: {@code <expiresEpochSeconds>.<base64url(hmac)>}. The key is
 * derived from JWT_SECRET with a purpose label, so a photo token can never be
 * replayed as anything else that secret signs.
 */
@Component
public class PhotoUploadTokens {

    private final byte[] key;
    private final long ttlSeconds;
    private final Clock clock;

    @Autowired
    public PhotoUploadTokens(JwtProperties jwt, AppProperties properties) {
        this(jwt.getSecret(), properties.getPhotos().getTokenHours() * 3600L, Clock.systemUTC());
    }

    PhotoUploadTokens(String secret, long ttlSeconds, Clock clock) {
        this.key = hmac(secret.getBytes(StandardCharsets.UTF_8),
                "civicpulse:photo-upload-token:v1".getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    public String issue(long complaintId) {
        long expires = clock.instant().getEpochSecond() + ttlSeconds;
        return expires + "." + sign(complaintId, expires);
    }

    /** True only for an unexpired token issued for exactly this complaint. */
    public boolean verify(String token, long complaintId) {
        if (token == null) return false;
        int dot = token.indexOf('.');
        if (dot <= 0) return false;
        long expires;
        try {
            expires = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (clock.instant().getEpochSecond() > expires) return false;
        byte[] expected = sign(complaintId, expires).getBytes(StandardCharsets.US_ASCII);
        byte[] given = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given);
    }

    private String sign(long complaintId, long expires) {
        byte[] mac = hmac(key, (complaintId + ":" + expires).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
