package com.civicpulse.backend_spring.service.auth;

import com.civicpulse.backend_spring.config.AppProperties;
import com.civicpulse.backend_spring.entity.User;
import com.civicpulse.backend_spring.enums.AuthTokenPurpose;
import com.civicpulse.backend_spring.enums.RevocationReason;
import com.civicpulse.backend_spring.exception.ValidationException;
import com.civicpulse.backend_spring.repository.UserRepository;
import com.civicpulse.backend_spring.service.email.AuthEmailComposer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Self-service password reset.
 *
 * Two properties this flow lives or dies by:
 *
 * NO ENUMERATION. {@link #requestReset} behaves identically whether or not the
 * address is registered — same 204, same shape, and the send is asynchronous so
 * response time does not give it away either. A "no account with that email"
 * message would hand anyone a way to test addresses in bulk, which is the same
 * leak {@code AuthService} spends a whole BCrypt verification to avoid on login.
 *
 * RESET KILLS EVERY SESSION. The usual reason someone resets a password is that
 * they think another person has it — and that person may be signed in right now.
 * Changing the password without revoking sessions would leave the intruder
 * exactly where they were, which is the failure this flow exists to prevent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenService authTokenService;
    private final RefreshTokenService refreshTokenService;
    private final AuthEmailComposer emailComposer;
    private final AppProperties appProperties;

    /**
     * Mails a reset link if the address belongs to an account. Callers must
     * answer the same way regardless — nothing here reports which it was.
     */
    @Transactional
    public void requestReset(String email) {
        Optional<User> found = userRepository.findByEmail(normalizeEmail(email));

        if (found.isEmpty()) {
            // Logged at debug, never returned. Someone typing their own address
            // wrong looks identical to someone probing for accounts, and only
            // the operator gets to tell them apart.
            log.debug("Password reset requested for an address with no account");
            return;
        }

        User user = found.get();
        Duration ttl = Duration.ofMinutes(appProperties.getEmail().getResetTtlMinutes());
        String token = authTokenService.issue(user, AuthTokenPurpose.PASSWORD_RESET, ttl);

        emailComposer.sendPasswordReset(user.getEmail(), user.getName(), token);
        log.info("Password reset link issued for user {}", user.getId());
    }

    /**
     * Redeems a reset link and sets the new password.
     *
     * @throws ValidationException if the token is unknown, expired, used, or of
     *         the wrong purpose
     */
    @Transactional
    public void reset(String token, String newPassword) {
        Long userId = authTokenService.consume(token, AuthTokenPurpose.PASSWORD_RESET);

        User user = userRepository.findById(userId)
                // The token was valid but the account is gone — deleted between
                // the request and the click.
                .orElseThrow(() -> new ValidationException(
                        "That link is invalid or has expired. Request a new one."));

        user.setPasswordHash(passwordEncoder.encode(newPassword));

        // Holding a reset link proves control of the mailbox, which is the same
        // thing verification asks for — so an unverified account becomes
        // verified here rather than making the user do it twice.
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(LocalDateTime.now());
        }
        userRepository.save(user);

        // Everything signed in with the OLD password is now signed out,
        // including whoever prompted the reset.
        int revoked = refreshTokenService.revokeAllForUser(userId, RevocationReason.PASSWORD_RESET);
        log.info("Password reset for user {} — {} session tokens revoked", userId, revoked);
    }

    /** Must match AuthService: emails are case-insensitive identifiers. */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
